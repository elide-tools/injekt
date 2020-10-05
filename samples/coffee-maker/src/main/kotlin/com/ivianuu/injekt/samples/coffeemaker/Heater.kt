package com.ivianuu.injekt.samples.coffeemaker

import com.ivianuu.injekt.Binding

interface Heater {
    fun on()
    fun off()
    val isHot: Boolean
}

@Binding(CoffeeComponent::class)
class ElectricHeater : Heater {
    private var heating: Boolean = false

    override fun on() {
        println("~ ~ ~ heating ~ ~ ~")
        heating = true
    }

    override fun off() {
        heating = false
    }

    override val isHot: Boolean
        get() = heating
}
