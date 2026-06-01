package org.cubexmc.metro.train;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;

public class TrainConsist {

    private final List<Minecart> cars = new ArrayList<>();

    public void addCar(Minecart car) {
        cars.add(car);
    }

    public List<Minecart> getCars() {
        return Collections.unmodifiableList(cars);
    }

    public Minecart getLeadCar() {
        return cars.isEmpty() ? null : cars.get(0);
    }

    public boolean contains(Minecart car) {
        return cars.contains(car);
    }

    public void setVelocity(Vector velocity) {
        for (Minecart car : cars) {
            if (car == null || car.isDead()) continue;
            car.setVelocity(velocity);
        }
    }

    public void zeroVelocity() {
        setVelocity(new Vector(0, 0, 0));
    }

    public void clear() {
        cars.clear();
    }
}


