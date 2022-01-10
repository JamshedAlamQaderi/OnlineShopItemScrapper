package com.jaq.web_scraper.chaldal

import com.github.doyaaaaaken.kotlincsv.client.CsvFileWriter
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

inline fun <reified T : Any> CsvFileWriter.withHeader(clazz: KClass<T>): CsvFileWriter {
    this.writeRow(
        clazz.memberProperties.map { it.name }
    )
    return this
}

inline fun <reified T : Any> CsvFileWriter.writeObjectRow(row: T): CsvFileWriter {
    val values = T::class.memberProperties.map { it.get(row) }
    this.writeRow(values)
    return this
}

inline fun <reified T : Any> CsvFileWriter.writeObjectRows(row: List<T>): CsvFileWriter {
    row.forEach { this.writeObjectRow(it) }
    return this
}