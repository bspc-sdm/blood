package com.lifolo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
class LifoloApplication

fun main(args: Array<String>) {
    runApplication<LifoloApplication>(*args)
}
