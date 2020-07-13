package com.ypwang.plugin.handler

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