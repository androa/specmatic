package `in`.specmatic.core

interface Hook {
    fun readContract(path: String): String
}