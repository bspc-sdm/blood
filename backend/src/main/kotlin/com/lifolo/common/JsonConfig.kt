package com.lifolo.common

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * JSON output like Node's JSON.stringify: whole doubles are written without ".0" (24.0 → 24),
 * NaN/Infinity become null. Nulls inside maps are kept (Node keeps explicit nulls).
 * Spring Boot registers every Module bean on the shared ObjectMapper.
 */
@Configuration
class JsonConfig {
    @Bean
    fun jsNumbersModule(): Module = SimpleModule("js-numbers").apply {
        addSerializer(Double::class.javaObjectType, JsDoubleSerializer())
        addSerializer(java.lang.Double.TYPE, JsDoubleSerializer())
        addSerializer(Float::class.javaObjectType, JsFloatSerializer())
    }

    class JsDoubleSerializer : JsonSerializer<Double>() {
        override fun serialize(value: Double, gen: JsonGenerator, serializers: SerializerProvider) {
            when {
                !value.isFinite() -> gen.writeNull()
                value == Math.floor(value) && Math.abs(value) < 1e15 -> gen.writeNumber(value.toLong())
                else -> gen.writeNumber(value)
            }
        }
    }

    class JsFloatSerializer : JsonSerializer<Float>() {
        override fun serialize(value: Float, gen: JsonGenerator, serializers: SerializerProvider) {
            JsDoubleSerializer().serialize(value.toDouble(), gen, serializers)
        }
    }
}
