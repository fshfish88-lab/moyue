package com.moyue.reader.parser

import com.moyue.reader.core.model.ParseInput
import com.moyue.reader.core.model.ParsedBook

fun interface BookParser {
    suspend fun parse(input: ParseInput): ParsedBook
}
