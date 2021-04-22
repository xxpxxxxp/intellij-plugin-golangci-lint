package com.ypwang.plugin.handler

import java.util.*

fun extractQuote(s: String, count: Int = 1): List<String> {
    val rst = mutableListOf<String>()
    var begin = s.indexOf('`') + 1
    var end = s.indexOf('`', begin)

    for (i in 0 until count) {
        if (end == -1)
            break

        rst.add(s.substring(begin, end))
        begin = s.indexOf('`', end + 1) + 1
        end = s.indexOf('`', begin)
    }

    return rst
}

// speed up pattern matching by word tree
// due to the special need of our matching, we will not have dup words, and a word must not start with another word
// a node will either have sub-entry(s), or an attachment
data class WordTree<T: Any>(var entry: MutableMap<Char, WordTree<T>>? = null, var attachment: Pair<String, T>? = null)

private fun <T: Any> add(root: WordTree<T>, word: String, i: Int, attachment: T) {
    var cur = root
    var idx = i
    while (idx < word.length) {
        cur = cur.entry?.get(word[idx]) ?: break
        idx++
    }

    if (cur.entry != null) {
        // simply put a new entry
        cur.entry!![word[idx]] = WordTree(attachment = word to attachment)
    } else {
        val (xWord, xAttachment) = cur.attachment!!
        cur.attachment = null
        cur.entry = mutableMapOf(word[idx] to WordTree(attachment = word to attachment))
        add(cur, xWord, idx, xAttachment)
    }
}

fun <T: Any> add(root: WordTree<T>, word: String, attachment: T) = add(root, word, 0, attachment)

fun <T: Any> find(root: WordTree<T>, word: String): Optional<T> {
    var cur: WordTree<T>? = root
    var idx = 0
    while (idx < word.length && cur?.entry != null) {
        cur = cur.entry?.get(word[idx++])
    }

    return Optional.ofNullable(cur?.attachment?.let { if (word.startsWith(it.first)) it.second else null })
}

//fun main() {
//    val tree = WordTree<Int>(entry = mutableMapOf())
//    add(tree, "abcd", 1)
//    add(tree, "abce", 2)
//    add(tree, "bdefggg", 3)
//    add(tree, "bdeggg", 4)
//    add(tree, "beggg", 5)
//
//    println(find(tree, "abcd"))
//    println(find(tree, "abce"))
//    println(find(tree, "bdefggg"))
//    println(find(tree, "bdeggg"))
//    println(find(tree, "beggg"))
//    println(find(tree, "abcg"))
//
//    var queue = mutableListOf(tree)
//    var level = 0
//
//    while (queue.isNotEmpty()) {
//        println("level ${level++}")
//        val next = mutableListOf<WordTree<Int>>()
//
//        for ((i, node) in queue.withIndex()) {
//            if (node.entry != null) {
//                for ((c, tn) in node.entry!!) {
//                    println("key $c")
//                    next.add(tn)
//                }
//            }
//
//            if (node.attachment != null) {
//                println("attachment ${node.attachment!!}")
//            }
//
//            println("node separator")
//        }
//
//        queue = next
//    }
//}