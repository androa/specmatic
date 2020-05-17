@file:JvmName("StubUtils")
package run.qontract.fake

import run.qontract.consoleLog
import run.qontract.core.CONTRACT_EXTENSION
import run.qontract.core.ContractBehaviour
import run.qontract.core.DATA_DIR_SUFFIX
import run.qontract.core.utilities.readFile
import run.qontract.core.value.StringValue
import run.qontract.mock.MockScenario
import run.qontract.mock.NoMatchingScenario
import run.qontract.mock.stringToMockScenario
import java.io.File

fun createStubFromContractAndData(contractGherkin: String, dataDirectory: String, host: String = "localhost", port: Int = 9000): ContractStub {
    val contractBehaviour = ContractBehaviour(contractGherkin)

    val mocks = (File(dataDirectory).listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()).map { file ->
        println("Loading data from ${file.name}")

        stringToMockScenario(StringValue(file.readText(Charsets.UTF_8)))
                .also {
                    contractBehaviour.matchingMockResponse(it)
                }
    }

    return ContractFake(listOf(Pair(contractBehaviour, mocks)), host, port, ::consoleLog)
}

fun allContractsFromDirectory(dirContainingContracts: String): List<String> =
    File(dirContainingContracts).listFiles()?.filter { it.extension == CONTRACT_EXTENSION }?.map { it.absolutePath } ?: emptyList()

fun createStubFromContracts(contractPaths: List<String>, dataDirPaths: List<String>, host: String = "localhost", port: Int = 9000): ContractStub {
    val dataDirs = dataDirPaths.map { File(it) }
    if(dataDirs.any { !it.exists() || !it.isDirectory }) throw Exception("Data directory $dataDirPaths does not exist.")

    val behaviours = contractPaths.map { path ->
        Pair(File(path), ContractBehaviour(readFile(path)))
    }

    val dataFiles = dataDirs.flatMap { it.listFiles()?.toList() ?: emptyList<File>() }.filter { it.extension == "json" }
    val mockData = dataFiles.map { Pair(it, stringToMockScenario(StringValue(it.readText()))) }

    val contractInfo = mockData.mapNotNull { (mockFile, mock) ->
        val matchResults = behaviours.asSequence().map { (contractFile, behaviour) ->
            try {
                val kafkaMessage = mock.kafkaMessage
                if(kafkaMessage != null) {
                    behaviour.assertMatchesMockKafkaMessage(kafkaMessage)
                } else {
                    behaviour.matchingMockResponse(mock.request, mock.response)
                }
                Pair(behaviour, null)
            } catch (e: NoMatchingScenario) {
                Pair(null, Pair(e, contractFile))
            }
        }

        when(val behaviour = matchResults.mapNotNull { it.first }.firstOrNull()) {
            null -> {
                println(matchResults.mapNotNull { it.second }.map { (exception, contractFile) ->
                    "${mockFile.absolutePath} didn't match ${contractFile.absolutePath}${System.lineSeparator()}${exception.message}"
                }.joinToString("${System.lineSeparator()}${System.lineSeparator()}"))
                null
            }
            else -> Pair(behaviour, mock)
        }
    }.groupBy { it.first }.mapValues { it.value.map { it.second } }.entries.map { Pair(it.key, it.value)}

    dataFiles?.let {
        println("Loaded data from:${System.lineSeparator()}${it.joinToString(System.lineSeparator())}")
    }

    return ContractFake(contractInfo, host, port, ::consoleLog)
}

fun createStubFromContracts(contractPaths: List<String>, host: String = "localhost", port: Int = 9000): ContractStub {
    val contractInfo = contractPaths.map { path ->
        val contractGherkin = readFile(path)
        val contractBehaviour = ContractBehaviour(contractGherkin)
        val stubInfo = loadStubInformation(path)
        Pair(contractBehaviour, stubInfo)
    }

    return ContractFake(contractInfo, host, port, ::consoleLog)
}

private fun loadStubInformation(filePath: String): List<MockScenario> =
        stubDataFiles(filePath).map { file ->
            println("Loading data from ${file.name}")

            stringToMockScenario(StringValue(file.readText(Charsets.UTF_8)))
        }

private fun stubDataFiles(path: String): List<File> {
    val contractFile = File(path)
    val stubDataDir = File("${contractFile.absoluteFile.parent}/${contractFile.nameWithoutExtension}$DATA_DIR_SUFFIX")
    println("Loading data files from ${stubDataDir.absolutePath} ")

    return stubDataDir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
}
