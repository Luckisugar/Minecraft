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

import java.util.ArrayList;
import java.util.List;

public final class FixedVoltageBudget {
    public record Allocation(double supplyVoltage, double remainingVoltage, List<Double> receivedVoltages) {
        public Allocation {
            receivedVoltages = List.copyOf(receivedVoltages);
        }
    }

    private FixedVoltageBudget() {
    }

    public static Allocation allocate(List<Double> sourceVoltages, List<Double> loadRequirements) {
        double supply = sumNonNegative(sourceVoltages, "Source voltage");
        double demand = sumNonNegative(loadRequirements, "Load voltage");
        if(!Double.isFinite(demand))
            throw new IllegalArgumentException("Total load voltage must be finite");

        double scale = demand == 0 ? 1 : Math.min(1, supply / demand);
        var received = new ArrayList<Double>(loadRequirements.size());
        double used = 0;
        for(double requirement : loadRequirements) {
            double voltage = requirement * scale;
            received.add(voltage);
            used += voltage;
        }
        return new Allocation(supply, Math.max(0, supply - used), received);
    }

    private static double sumNonNegative(List<Double> values, String name) {
        double total = 0;
        for(Double boxedValue : values) {
            if(boxedValue == null)
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            double value = boxedValue;
            if(!Double.isFinite(value) || value < 0)
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            total += value;
        }
        if(!Double.isFinite(total))
            throw new IllegalArgumentException("Total " + name.toLowerCase() + " must be finite");
        return total;
    }
}
