package com.example.engine

import java.util.Locale
import androidx.compose.runtime.mutableStateMapOf

enum class ComponentType {
    RESISTOR,
    CAPACITOR,
    INDUCTOR,
    DIODE,
    VOLTAGE_SOURCE,
    CURRENT_SOURCE,
    GROUND,
    TRANSISTOR_NPN,
    MOSFET_N,
    THYRISTOR,
    RELAY,
    TRIAC,
    OPAMP,
    SUBCIRCUIT,
    PORT
}

data class GridPoint(val x: Int, val y: Int) {
    override fun toString(): String = "($x,$y)"
}

enum class Orientation(val degrees: Float) {
    DEG_0(0f),
    DEG_90(90f),
    DEG_180(180f),
    DEG_270(270f)
}

data class Component(
    val id: String,
    val type: ComponentType,
    val name: String,
    val valueStr: String, // String value entered by user, e.g. "1k", "10u", "5", "SINE(0 10 1k)"
    val gridX: Int, // Grid placement X coordinate
    val gridY: Int, // Grid placement Y coordinate
    val orientation: Orientation = Orientation.DEG_0
) {
    // Rotates/translates local coordinate offset (dx, dy) to global grid coordinate depending on orientation
    fun translateLocalToGlobal(dx: Int, dy: Int): GridPoint {
        return when (orientation) {
            Orientation.DEG_0 -> GridPoint(gridX + dx, gridY + dy)
            Orientation.DEG_90 -> GridPoint(gridX - dy, gridY + dx)
            Orientation.DEG_180 -> GridPoint(gridX - dx, gridY - dy)
            Orientation.DEG_270 -> GridPoint(gridX + dy, gridY - dx)
        }
    }

    // Return coordinate pins based on rotation and component sizes
    fun getPins(): List<GridPoint> {
        val list = mutableListOf<GridPoint>()
        when (type) {
            ComponentType.GROUND -> {
                // Ground has a single pin at the center/top
                list.add(GridPoint(gridX, gridY))
            }
            ComponentType.PORT -> {
                // Port has a single pin at grid position
                list.add(GridPoint(gridX, gridY))
            }
            ComponentType.RESISTOR, ComponentType.CAPACITOR, ComponentType.INDUCTOR,
            ComponentType.DIODE, ComponentType.VOLTAGE_SOURCE, ComponentType.CURRENT_SOURCE -> {
                // Two-pin components have typical layout span of 2 grid spacing
                list.add(translateLocalToGlobal(-1, 0))
                list.add(translateLocalToGlobal(1, 0))
            }
            ComponentType.TRANSISTOR_NPN -> {
                list.add(translateLocalToGlobal(-1, 0))  // pin 0: Base
                list.add(translateLocalToGlobal(1, -1))  // pin 1: Collector
                list.add(translateLocalToGlobal(1, 1))   // pin 2: Emitter
            }
            ComponentType.MOSFET_N -> {
                list.add(translateLocalToGlobal(-1, 0))  // pin 0: Gate
                list.add(translateLocalToGlobal(1, -1))  // pin 1: Drain
                list.add(translateLocalToGlobal(1, 1))   // pin 2: Source
            }
            ComponentType.THYRISTOR -> {
                list.add(translateLocalToGlobal(-1, 0))  // pin 0: Anode
                list.add(translateLocalToGlobal(1, 0))   // pin 1: Cathode
                list.add(translateLocalToGlobal(0, 1))   // pin 2: Gate
            }
            ComponentType.RELAY -> {
                list.add(translateLocalToGlobal(-1, -1)) // pin 0: Coil 1
                list.add(translateLocalToGlobal(-1, 1))  // pin 1: Coil 2
                list.add(translateLocalToGlobal(1, -1))  // pin 2: Switch Contact 1
                list.add(translateLocalToGlobal(1, 1))   // pin 3: Switch Contact 2
            }
            ComponentType.TRIAC -> {
                list.add(translateLocalToGlobal(-1, 0))  // pin 0: Main Terminal 1 (MT1)
                list.add(translateLocalToGlobal(1, 0))   // pin 1: Main Terminal 2 (MT2)
                list.add(translateLocalToGlobal(0, 1))   // pin 2: Gate
            }
            ComponentType.OPAMP -> {
                list.add(translateLocalToGlobal(-1, -1)) // pin 0: Inverting Input (-)
                list.add(translateLocalToGlobal(-1, 1))  // pin 1: Non-Inverting Input (+)
                list.add(translateLocalToGlobal(1, 0))   // pin 2: Output
            }
            ComponentType.SUBCIRCUIT -> {
                val template = SubcircuitRegistry.templates[valueStr]
                if (template != null) {
                    template.ports.forEach { localPt ->
                        list.add(translateLocalToGlobal(localPt.x, localPt.y))
                    }
                } else {
                    // Fallback to simple left and right pins
                    list.add(translateLocalToGlobal(-1, 0))
                    list.add(translateLocalToGlobal(1, 0))
                }
            }
        }
        return list
    }
}

data class SubcircuitTemplate(
    val id: String, // Unique identifier / template name, e.g. "filter"
    val name: String, // Display name
    val ports: List<GridPoint>, // External port locations in the subcircuit's local workspace
    val portNames: List<String>, // Label names for each port (e.g., In, Out, GND)
    val components: List<Component>,
    val wires: List<Wire>
)

object SubcircuitRegistry {
    val templates = mutableStateMapOf<String, SubcircuitTemplate>()
}

data class Wire(
    val id: String,
    val start: GridPoint,
    val end: GridPoint
) {
    // Check if a point lies on the wire segment (assuming horizontal or vertical segments)
    fun contains(pt: GridPoint): Boolean {
        if (start.x == end.x) {
            // Vertical wire
            val minY = minOf(start.y, end.y)
            val maxY = maxOf(start.y, end.y)
            return pt.x == start.x && pt.y in minY..maxY
        } else if (start.y == end.y) {
            // Horizontal wire
            val minX = minOf(start.x, end.x)
            val maxX = maxOf(start.x, end.x)
            return pt.y == start.y && pt.x in minX..maxX
        }
        // Diagonal segment fallback check
        return pt == start || pt == end
    }
}

enum class SimType {
    TRANSIENT,
    DC_SWEEP,
    OP,
    AC
}

data class SimulationSettings(
    val type: SimType = SimType.TRANSIENT,
    
    // Transient params
    val stopTimeStr: String = "10m",
    val stepTimeStr: String = "0.1m",
    
    // DC Sweep params
    val sweepSource: String = "V1",
    val sweepStart: Double = 0.0,
    val sweepStop: Double = 10.0,
    val sweepStep: Double = 0.5,

    // AC Sweep params
    val acStartFreqStr: String = "10",
    val acStopFreqStr: String = "100k",
    val acPointsCount: Int = 100
)

object SpiceUtils {
    /**
     * Parses SPICE values like "1k", "10u", "2.2Meg", "4.7m" into doubles.
     * LTspice standards:
     * - "M" or "m" is milli (1e-3)
     * - "Meg" or "meg" is Mega (1e6)
     * - "k" is kilo (1e3)
     * - "u" or "µ" is micro (1e-6)
     * - "n" is nano (1e-9)
     * - "p" is pico (1e-12)
     * - "f" is femto (1e-15)
     */
    fun parseValue(valueStr: String): Double {
        if (valueStr.isBlank()) return 0.0
        val trimmed = valueStr.trim().lowercase(Locale.ROOT)
        
        // Extract leading numeric part
        val sbNum = StringBuilder()
        var hasDecimal = false
        var index = 0
        while (index < trimmed.length) {
            val char = trimmed[index]
            if (char.isDigit()) {
                sbNum.append(char)
            } else if (char == '.' && !hasDecimal) {
                sbNum.append(char)
                hasDecimal = true
            } else if (char == '-' && index == 0) {
                sbNum.append(char)
            } else {
                break
            }
            index++
        }
        
        if (sbNum.isEmpty()) return 1.0 // Sane default
        
        val baseVal = try {
            sbNum.toString().toDouble()
        } catch (e: Exception) {
            1.0
        }
        
        if (index >= trimmed.length) return baseVal
        
        val suffix = trimmed.substring(index)
        val multiplier = when {
            suffix.startsWith("f") -> 1e-15
            suffix.startsWith("p") -> 1e-12
            suffix.startsWith("n") -> 1e-9
            suffix.startsWith("u") || suffix.startsWith("μ") || suffix.startsWith("µ") -> 1e-6
            suffix.startsWith("meg") -> 1e6
            suffix.startsWith("m") -> 1e-3 // Note: LTspice 'm' is Milli, 'meg' is Mega.
            suffix.startsWith("k") -> 1e3
            suffix.startsWith("g") -> 1e9
            suffix.startsWith("t") -> 1e12
            else -> 1.0
        }
        
        return baseVal * multiplier
    }

    /**
     * Parse SINE source parameter values from a string like "SINE(0 10 1k 0 0)"
     * Format: SINE(offset amp freq [delay damp phase])
     */
    fun parseSineParams(sineStr: String): SineParameters {
        val default = SineParameters(0.0, 1.0, 1000.0)
        val content = sineStr.substringAfter("sine(").substringBefore(")")
        val tokens = content.split(" ", ",", "\t").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return default
        
        val offset = parseValue(tokens.getOrNull(0) ?: "0")
        val amp = parseValue(tokens.getOrNull(1) ?: "1")
        val freq = parseValue(tokens.getOrNull(2) ?: "1k")
        val delay = parseValue(tokens.getOrNull(3) ?: "0")
        val damp = parseValue(tokens.getOrNull(4) ?: "0")
        val phase = parseValue(tokens.getOrNull(5) ?: "0")
        
        return SineParameters(offset, amp, freq, delay, damp, phase)
    }

    /**
     * Parse PULSE source parameter values from a string like "PULSE(0 5 1m 1u 1u 5m 10m)"
     * Format: PULSE(v1 v2 [delay rise fall width period])
     */
    fun parsePulseParams(pulseStr: String): PulseParameters {
        val default = PulseParameters(0.0, 5.0, 0.0, 1e-6, 1e-6, 0.005, 0.01)
        val content = pulseStr.substringAfter("pulse(").substringBefore(")")
        val tokens = content.split(" ", ",", "\t").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return default
        
        val v1 = parseValue(tokens.getOrNull(0) ?: "0")
        val v2 = parseValue(tokens.getOrNull(1) ?: "5")
        val delay = parseValue(tokens.getOrNull(2) ?: "0")
        val rise = parseValue(tokens.getOrNull(3) ?: "1u")
        val fall = parseValue(tokens.getOrNull(4) ?: "1u")
        val width = parseValue(tokens.getOrNull(5) ?: "5m")
        val period = parseValue(tokens.getOrNull(6) ?: "10m")
        
        return PulseParameters(v1, v2, delay, rise, fall, width, period)
    }
}

data class SineParameters(
    val offset: Double,
    val amplitude: Double,
    val frequency: Double,
    val delay: Double = 0.0,
    val damping: Double = 0.0,
    val phase: Double = 0.0
) {
    fun evaluate(time: Double): Double {
        if (time < delay) return offset
        val tEff = time - delay
        return offset + amplitude * Math.exp(-damping * tEff) * Math.sin(2.0 * Math.PI * frequency * tEff + Math.toRadians(phase))
    }
}

data class PulseParameters(
    val v1: Double,
    val v2: Double,
    val delay: Double,
    val riseTime: Double,
    val fallTime: Double,
    val width: Double,
    val period: Double
) {
    fun evaluate(time: Double): Double {
        if (time < delay) return v1
        val tMod = (time - delay) % period
        val r = if (riseTime <= 0) 1e-12 else riseTime
        val f = if (fallTime <= 0) 1e-12 else fallTime
        val w = width
        
        return when {
            tMod < r -> {
                // Rising edge
                v1 + (v2 - v1) * (tMod / r)
            }
            tMod < r + w -> {
                // High period
                v2
            }
            tMod < r + w + f -> {
                // Falling edge
                v2 - (v2 - v1) * ((tMod - r - w) / f)
            }
            else -> {
                // Low period
                v1
            }
        }
    }
}

data class SimResult(
    val timePoints: List<Double>, // x-axis (can be time for transient, swept value for DC sweep)
    val xlabel: String = "Time (s)",
    val nodeVoltages: Map<String, List<Double>>, // Node name -> values
    val currents: Map<String, List<Double>> = emptyMap(), // Component/source name -> currents
    val nodeIndexes: Map<String, Int> = emptyMap() // Map string name to MNA index for reference
)
