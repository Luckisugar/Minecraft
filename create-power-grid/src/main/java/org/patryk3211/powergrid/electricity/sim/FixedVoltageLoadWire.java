/*
 * Copyright 2025 patryk3211
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.patryk3211.powergrid.electricity.sim;

import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

public class FixedVoltageLoadWire extends SwitchedWire {
    private double requiredVoltage;
    private double ratedAmps;
    private double receivedVoltage;
    private double ampScale = 1;
    private boolean enabled = true;

    public FixedVoltageLoadWire(double requiredVoltage, IElectricNode positive, IElectricNode negative) {
        this(requiredVoltage, positive, negative, true);
    }

    public FixedVoltageLoadWire(double requiredVoltage, IElectricNode positive, IElectricNode negative, boolean initialState) {
        super(1, positive, negative, initialState);
        setRating(requiredVoltage, 0);
    }

    public double requiredVoltage() {
        return requiredVoltage;
    }

    public double ratedAmps() {
        return ratedAmps;
    }

    public void setRatedAmps(double ratedAmps) {
        if(!Double.isFinite(ratedAmps) || ratedAmps < 0)
            throw new IllegalArgumentException("Rated amps must be finite and non-negative");
        this.ratedAmps = ratedAmps;
    }

    public void setRating(double requiredVoltage, double ratedAmps) {
        if(!Double.isFinite(requiredVoltage) || requiredVoltage < 0)
            throw new IllegalArgumentException("Required voltage must be finite and non-negative");
        setRatedAmps(ratedAmps);
        this.requiredVoltage = requiredVoltage;
    }

    public double receivedVoltage() {
        return receivedVoltage;
    }

    public double outputScale() {
        var voltageScale = requiredVoltage == 0 ? 1 : Math.min(1, Math.abs(receivedVoltage) / requiredVoltage);
        return voltageScale * ampScale;
    }

    public boolean isEnabled() {
        return enabled && getState();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setOperatingPoint(double receivedVoltage, double ampScale) {
        this.receivedVoltage = receivedVoltage;
        this.ampScale = ampScale;
    }

    @Override
    public double potentialDifference() {
        if(requiredVoltage == 0)
            return super.potentialDifference();
        return receivedVoltage;
    }

    @Override
    public double current() {
        return 0;
    }

    @Override
    public double power() {
        return outputScale();
    }

    @Override
    public double internalPower() {
        return 0;
    }
}
