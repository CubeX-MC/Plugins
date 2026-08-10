package org.cubexmc.metro.train

import org.bukkit.entity.Minecart
import org.bukkit.util.Vector
import java.util.Collections

class TrainConsist {

    private val cars: MutableList<Minecart> = ArrayList()

    fun addCar(car: Minecart) {
        cars.add(car)
    }

    fun getCars(): List<Minecart> = Collections.unmodifiableList(cars)

    fun getLeadCar(): Minecart? = if (cars.isEmpty()) null else cars[0]

    fun contains(car: Minecart?): Boolean = cars.contains(car)

    fun setVelocity(velocity: Vector) {
        for (car in cars) {
            if (car.isDead) continue
            car.velocity = velocity
        }
    }

    fun zeroVelocity() {
        setVelocity(Vector(0, 0, 0))
    }

    fun clear() {
        cars.clear()
    }
}
