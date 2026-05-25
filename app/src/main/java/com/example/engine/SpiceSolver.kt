package com.example.engine

import android.util.Log
import kotlin.math.abs

class SpiceSolver {

    // --- Complex Arithmetic Helpers for AC Analysis ---
    data class Complex(val r: Double, val i: Double) {
        operator fun plus(o: Complex) = Complex(r + o.r, i + o.i)
        operator fun minus(o: Complex) = Complex(r - o.r, i - o.i)
        operator fun times(o: Complex) = Complex(r * o.r - i * o.i, r * o.i + i * o.r)
        operator fun div(o: Complex): Complex {
            val d = o.r * o.r + o.i * o.i
            val den = if (d == 0.0) 1e-15 else d
            return Complex(
                (r * o.r + i * o.i) / den,
                (i * o.r - r * o.i) / den
            )
        }
        fun magnitude() = kotlin.math.sqrt(r * r + i * i)
    }

    class UnionFind<T> {
        private val parent = mutableMapOf<T, T>()

        fun find(element: T): T {
            var root = element
            while (parent[root] != null) {
                root = parent[root]!!
            }
            var curr = element
            while (curr != root) {
                val next = parent[curr] ?: root
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(e1: T, e2: T) {
            val root1 = find(e1)
            val root2 = find(e2)
            if (root1 != root2) {
                parent[root1] = root2
            }
        }
    }

    /**
     * Run simulation on schematic components and wires.
     */
    fun simulate(
        rawComponents: List<Component>,
        rawWires: List<Wire>,
        settings: SimulationSettings
    ): SimResult {
        val (components, wires) = flatten(rawComponents, rawWires)

        // --- 1. NETLISTING (Union-Find to merge connected pins & wires) ---
        val uf = UnionFind<GridPoint>()

        // Collect all active points
        val pins = components.flatMap { it.getPins() }
        val wirePoints = wires.flatMap { listOf(it.start, it.end) }
        val allPoints = (pins + wirePoints).distinct()

        // Union terminal points connected by wires
        for (wire in wires) {
            uf.union(wire.start, wire.end)
            // Also merge any other point lying along the wire segment
            for (pt in allPoints) {
                if (wire.contains(pt)) {
                    uf.union(wire.start, pt)
                }
            }
        }

        // Merge components' overlapping terminals sitting on exact same grid coordinates
        for (pt1 in allPoints) {
            for (pt2 in allPoints) {
                if (pt1 == pt2) {
                    uf.union(pt1, pt2)
                }
            }
        }

        // Identify Ground root
        val groundPins = components.filter { it.type == ComponentType.GROUND }.flatMap { it.getPins() }
        val groundRoot = if (groundPins.isNotEmpty()) uf.find(groundPins.first()) else null

        // Assign a unique Node identification (Node 0 is always GROUND)
        val rootToNodeNumber = mutableMapOf<GridPoint, Int>()
        var currentNodeCounter = 1

        if (groundRoot != null) {
            rootToNodeNumber[groundRoot] = 0
        }

        val nodeNames = mutableMapOf<Int, String>()
        nodeNames[0] = "0 (GND)"

        // Map every grid point to a node number
        val ptToNode = mutableMapOf<GridPoint, Int>()
        for (pt in allPoints) {
            val root = uf.find(pt)
            var nodeNum = rootToNodeNumber[root]
            if (nodeNum == null) {
                nodeNum = currentNodeCounter++
                rootToNodeNumber[root] = nodeNum
                nodeNames[nodeNum] = "N$nodeNum"
            }
            ptToNode[pt] = nodeNum
        }

        // If no ground components were placed, designate the first node as ground to prevent solver crash
        if (groundRoot == null) {
            Log.w("SpiceSolver", "No ground placed, designating default ground.")
            val firstRoot = allPoints.firstOrNull()?.let { uf.find(it) }
            if (firstRoot != null) {
                rootToNodeNumber[firstRoot] = 0
                nodeNames[0] = "0 (GND)"
                // Re-evaluate
                for (pt in allPoints) {
                    val root = uf.find(pt)
                    if (root == firstRoot) {
                        ptToNode[pt] = 0
                    }
                }
            }
        }

        val totalNodes = maxOf(1, rootToNodeNumber.values.maxOrNull()?.plus(1) ?: 1)

        // Map voltage sources
        val vSources = components.filter { it.type == ComponentType.VOLTAGE_SOURCE }
        val numVSources = vSources.size

        // Total MNA variables: (totalNodes - 1) + numVSources
        val mnaSize = (totalNodes - 1) + numVSources

        // If there are no nodes/components, return empty result
        if (mnaSize <= 0) {
            return SimResult(emptyList(), "Time (s)", emptyMap(), emptyMap())
        }

        // Delegate based on desired simulation command type
        return when (settings.type) {
            SimType.TRANSIENT -> {
                runTransient(components, settings, totalNodes, mnaSize, vSources, ptToNode, nodeNames)
            }
            SimType.OP -> {
                runOP(components, settings, totalNodes, mnaSize, vSources, ptToNode, nodeNames)
            }
            SimType.AC -> {
                runAC(components, settings, totalNodes, mnaSize, vSources, ptToNode, nodeNames)
            }
            SimType.DC_SWEEP -> {
                runDCSweep(components, settings, totalNodes, mnaSize, vSources, ptToNode, nodeNames)
            }
        }
    }

    // --- SUB-SOLVER 1: TRANSIENT SOLVER (.TRAN) ---
    private fun runTransient(
        components: List<Component>,
        settings: SimulationSettings,
        totalNodes: Int,
        mnaSize: Int,
        vSources: List<Component>,
        ptToNode: Map<GridPoint, Int>,
        nodeNames: Map<Int, String>
    ): SimResult {
        val stopTime = SpiceUtils.parseValue(settings.stopTimeStr)
        val stepTime = SpiceUtils.parseValue(settings.stepTimeStr)

        val timePoints = mutableListOf<Double>()
        val outputVoltages = mutableMapOf<Int, MutableList<Double>>()
        for (i in 0 until totalNodes) {
            outputVoltages[i] = mutableListOf()
        }
        val outputCurrents = mutableMapOf<String, MutableList<Double>>()
        vSources.forEach { outputCurrents[it.id] = mutableListOf() }

        val capacitorVoltages = mutableMapOf<String, Double>() // ID -> V_cap(t-h)
        val inductorCurrents = mutableMapOf<String, Double>()  // ID -> I_ind(t-h)

        // Initialize state variables to 0
        components.forEach { comp ->
            if (comp.type == ComponentType.CAPACITOR) {
                capacitorVoltages[comp.id] = 0.0
            } else if (comp.type == ComponentType.INDUCTOR) {
                inductorCurrents[comp.id] = 0.0
            }
        }

        var t = 0.0
        val stepsCount = (stopTime / stepTime).toInt() + 1
        val maxSteps = minOf(stepsCount, 1500)

        // Pre-parse source functions (Sine or Pulse)
        val vSourceEvaluators = vSources.map { vsrc ->
            val vStr = vsrc.valueStr.trim().lowercase()
            val evaluator: (Double) -> Double = when {
                vStr.startsWith("sine") -> { tVal -> SpiceUtils.parseSineParams(vsrc.valueStr).evaluate(tVal) }
                vStr.startsWith("pulse") -> { tVal -> SpiceUtils.parsePulseParams(vsrc.valueStr).evaluate(tVal) }
                else -> { _ -> SpiceUtils.parseValue(vsrc.valueStr) } 
            }
            vsrc.id to evaluator
        }.toMap()

        val diodeVoltages = mutableMapOf<String, Double>()
        components.filter { it.type == ComponentType.DIODE }.forEach { diodeVoltages[it.id] = 0.6 }

        val bjtVoltagesBE = mutableMapOf<String, Double>()
        val mosVoltagesGS = mutableMapOf<String, Double>()
        val mosVoltagesDS = mutableMapOf<String, Double>()
        val thyristorLatched = mutableMapOf<String, Boolean>()
        val triacLatched = mutableMapOf<String, Boolean>()
        val opampVoltagesDiff = mutableMapOf<String, Double>()

        components.filter { it.type == ComponentType.TRANSISTOR_NPN }.forEach { bjtVoltagesBE[it.id] = 0.6 }
        components.filter { it.type == ComponentType.MOSFET_N }.forEach {
            mosVoltagesGS[it.id] = 0.0
            mosVoltagesDS[it.id] = 0.0
        }
        components.filter { it.type == ComponentType.THYRISTOR }.forEach { thyristorLatched[it.id] = false }
        components.filter { it.type == ComponentType.TRIAC }.forEach { triacLatched[it.id] = false }
        components.filter { it.type == ComponentType.OPAMP }.forEach { opampVoltagesDiff[it.id] = 0.0 }

        for (step in 0 until maxSteps) {
            timePoints.add(t)

            var solvedX = DoubleArray(mnaSize)
            var nrIterations = 0
            val maxNrIterations = 8
            var converged = false

            while (nrIterations < maxNrIterations && !converged) {
                val A = Array(mnaSize) { DoubleArray(mnaSize) }
                val B = DoubleArray(mnaSize)

                for (i in 0 until (totalNodes - 1)) {
                    A[i][i] += 1e-12
                }

                components.forEach { comp ->
                    val cPins = comp.getPins()
                    val p = SpiceParameters(comp.valueStr)
                    when (comp.type) {
                        ComponentType.RESISTOR -> {
                            val rVal = maxOf(1e-9, p.getDouble("r", p.mainValue))
                            val G = 1.0 / rVal
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            stampConductance(A, nA, nB, G)
                        }
                        ComponentType.CAPACITOR -> {
                            val cVal = maxOf(1e-20, p.getDouble("c", p.mainValue))
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val G = cVal / stepTime
                            stampConductance(A, nA, nB, G)
                            
                            val vPrev = capacitorVoltages[comp.id] ?: 0.0
                            val Ieq = G * vPrev
                            stampCurrentSource(B, nA, nB, Ieq)
                        }
                        ComponentType.INDUCTOR -> {
                            val lVal = maxOf(1e-20, p.getDouble("l", p.mainValue))
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val G = stepTime / lVal
                            stampConductance(A, nA, nB, G)

                            val iPrev = inductorCurrents[comp.id] ?: 0.0
                            stampCurrentSource(B, nA, nB, iPrev)
                        }
                        ComponentType.CURRENT_SOURCE -> {
                            val iVal = p.getDouble("dc", p.mainValue)
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            stampCurrentSource(B, nA, nB, iVal)
                        }
                        ComponentType.DIODE -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            
                            val Is = p.getDouble("is", 1e-14)
                            val Vt = p.getDouble("vt", 0.02585)
                            val n = p.getDouble("n", p.getDouble("ncoef", 1.0))

                            val vdPrev = diodeVoltages[comp.id] ?: 0.6
                            val vdChecked = minOf(0.85, maxOf(-10.0, vdPrev))

                            val expTerm = Math.exp(vdChecked / (n * Vt))
                            val Id = Is * (expTerm - 1.0)
                            val Gd = (Is / (n * Vt)) * expTerm

                            stampConductance(A, nA, nB, Gd)
                            val Ieq = Id - Gd * vdChecked
                            stampCurrentSource(B, nA, nB, Ieq)
                        }
                        ComponentType.TRANSISTOR_NPN -> {
                            val nB = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nC = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nE = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val Is = p.getDouble("is", 1e-14)
                            val Vt = p.getDouble("vt", 0.02585)
                            val beta = p.getDouble("bf", p.getDouble("beta", 100.0))

                            val prevVbe = bjtVoltagesBE[comp.id] ?: 0.6
                            val vbeChecked = minOf(0.85, maxOf(-10.0, prevVbe))

                            val expTerm = Math.exp(vbeChecked / Vt)
                            val Ibe = Is * (expTerm - 1.0)
                            val Gbe = (Is / Vt) * expTerm

                            stampConductance(A, nB, nE, Gbe)
                            stampCurrentSource(B, nB, nE, Ibe - Gbe * vbeChecked)

                            if (nC > 0) {
                                if (nB > 0) A[nC - 1][nB - 1] += beta * Gbe
                                if (nE > 0) A[nC - 1][nE - 1] -= beta * Gbe
                            }
                            if (nE > 0) {
                                if (nB > 0) A[nE - 1][nB - 1] -= beta * Gbe
                                if (nE > 0) A[nE - 1][nE - 1] += beta * Gbe
                            }
                            val IeqIc = beta * (Ibe - Gbe * vbeChecked)
                            stampCurrentSource(B, nC, nE, IeqIc)
                        }
                        ComponentType.MOSFET_N -> {
                            val nG = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nD = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nS = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val Vth = p.getDouble("vto", p.getDouble("vth", p.getDouble("vth_n", 2.0)))
                            val betaMos = p.getDouble("kp", p.getDouble("betamos", 1e-3))

                            val prevVgs = mosVoltagesGS[comp.id] ?: 0.0
                            val prevVds = mosVoltagesDS[comp.id] ?: 0.0

                            val Ids: Double
                            val gm: Double
                            val gds: Double

                            if (prevVgs < Vth) {
                                Ids = 0.0
                                gm = 0.0
                                gds = 1e-12
                            } else if (prevVds < prevVgs - Vth) {
                                Ids = betaMos * (2.0 * (prevVgs - Vth) * prevVds - prevVds * prevVds)
                                gm = 2.0 * betaMos * prevVds
                                gds = 2.0 * betaMos * (prevVgs - Vth - prevVds)
                            } else {
                                Ids = betaMos * (prevVgs - Vth) * (prevVgs - Vth)
                                gm = 2.0 * betaMos * (prevVgs - Vth)
                                gds = 1e-5
                            }

                            stampConductance(A, nD, nS, gds)

                            if (nD > 0) {
                                if (nG > 0) A[nD - 1][nG - 1] += gm
                                if (nS > 0) A[nD - 1][nS - 1] -= gm
                            }
                            if (nS > 0) {
                                if (nG > 0) A[nS - 1][nG - 1] -= gm
                                if (nS > 0) A[nS - 1][nS - 1] += gm
                            }

                            val Ieq = Ids - gm * prevVgs - gds * prevVds
                            stampCurrentSource(B, nD, nS, Ieq)
                        }
                        ComponentType.THYRISTOR -> {
                            val nANode = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nK = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val rGate = p.getDouble("rgate", p.getDouble("rg", 1e5))
                            val rOn = p.getDouble("ron", 1.0)
                            val rOff = p.getDouble("roff", 1e7)

                            stampConductance(A, nG, nK, 1.0 / rGate)

                            val latched = thyristorLatched[comp.id] ?: false
                            if (latched) {
                                stampConductance(A, nANode, nK, 1.0 / rOn)
                            } else {
                                stampConductance(A, nANode, nK, 1.0 / rOff)
                            }
                        }
                        ComponentType.TRIAC -> {
                            val nMT1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nMT2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val rGate = p.getDouble("rgate", p.getDouble("rg", 1e5))
                            val rOn = p.getDouble("ron", 1.0)
                            val rOff = p.getDouble("roff", 1e7)

                            stampConductance(A, nG, nMT1, 1.0 / rGate)

                            val latched = triacLatched[comp.id] ?: false
                            if (latched) {
                                stampConductance(A, nMT2, nMT1, 1.0 / rOn)
                            } else {
                                stampConductance(A, nMT2, nMT1, 1.0 / rOff)
                            }
                        }
                        ComponentType.RELAY -> {
                            val nC1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nC2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nS1 = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                            val nS2 = if (cPins.size > 3) ptToNode[cPins[3]] ?: 0 else 0

                            val rCoil = p.getDouble("rcoil", p.getDouble("rc", 100.0))
                            val rOn = p.getDouble("ron", 0.1)
                            val rOff = p.getDouble("roff", 1e7)
                            val vTrigger = p.getDouble("vtrigger", p.getDouble("vt", 3.0))

                            stampConductance(A, nC1, nC2, 1.0 / rCoil)

                            val vC1Val = if (nC1 > 0) solvedX.getOrElse(nC1 - 1) { 0.0 } else 0.0
                            val vC2Val = if (nC2 > 0) solvedX.getOrElse(nC2 - 1) { 0.0 } else 0.0
                            val coilActive = abs(vC1Val - vC2Val) > vTrigger

                            if (coilActive) {
                                stampConductance(A, nS1, nS2, 1.0 / rOn)
                            } else {
                                stampConductance(A, nS1, nS2, 1.0 / rOff)
                            }
                        }
                        ComponentType.OPAMP -> {
                            val nInv = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nNon = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nOut = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val rOut = p.getDouble("rout", p.getDouble("ro", 50.0))
                            val gain = p.getDouble("gain", p.getDouble("a", 100000.0))
                            val vMax = p.getDouble("vmax", p.getDouble("vsat", 12.0))

                            stampConductance(A, nOut, 0, 1.0 / rOut)

                            val prevVdiff = opampVoltagesDiff[comp.id] ?: 0.0
                            val Gm = gain / rOut
                            val limitI = vMax / rOut
                            val Istamp = minOf(limitI, maxOf(-limitI, Gm * prevVdiff))
                            stampCurrentSource(B, 0, nOut, Istamp)
                        }
                        else -> {}
                    }
                }

                 vSources.forEachIndexed { idx, vsrc ->
                    val cPins = vsrc.getPins()
                    val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                    val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                    
                    val valueEval = vSourceEvaluators[vsrc.id]?.invoke(t) ?: 0.0
                    stampVoltageSource(A, B, nA, nB, idx, valueEval, totalNodes)
                }

                val x = solveMatrix(A, B)
                solvedX = x

                var allDevicesConverged = true
                components.forEach { comp ->
                    val cPins = comp.getPins()
                    when (comp.type) {
                        ComponentType.DIODE -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val vA = if (nA > 0) x[nA - 1] else 0.0
                            val vB = if (nB > 0) x[nB - 1] else 0.0
                            val newVd = vA - vB
                            val prevVd = diodeVoltages[comp.id] ?: 0.6
                            if (abs(newVd - prevVd) > 1e-3) {
                                allDevicesConverged = false
                            }
                            diodeVoltages[comp.id] = prevVd + 0.5 * (newVd - prevVd)
                        }
                        ComponentType.TRANSISTOR_NPN -> {
                            val nB = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nE = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                            val vB = if (nB > 0) x[nB - 1] else 0.0
                            val vE = if (nE > 0) x[nE - 1] else 0.0
                            val newVbe = vB - vE
                            val prevVbe = bjtVoltagesBE[comp.id] ?: 0.6
                            if (abs(newVbe - prevVbe) > 1e-3) {
                                allDevicesConverged = false
                            }
                            bjtVoltagesBE[comp.id] = prevVbe + 0.5 * (newVbe - prevVbe)
                        }
                        ComponentType.MOSFET_N -> {
                            val nG = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nD = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nS = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val vG = if (nG > 0) x[nG - 1] else 0.0
                            val vD = if (nD > 0) x[nD - 1] else 0.0
                            val vS = if (nS > 0) x[nS - 1] else 0.0

                            val newVgs = vG - vS
                            val newVds = vD - vS

                            val prevVgs = mosVoltagesGS[comp.id] ?: 0.0
                            val prevVds = mosVoltagesDS[comp.id] ?: 0.0

                            if (abs(newVgs - prevVgs) > 1e-3 || abs(newVds - prevVds) > 1e-3) {
                                allDevicesConverged = false
                            }
                            mosVoltagesGS[comp.id] = prevVgs + 0.5 * (newVgs - prevVgs)
                            mosVoltagesDS[comp.id] = prevVds + 0.5 * (newVds - prevVds)
                        }
                        ComponentType.THYRISTOR -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nK = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val vA = if (nA > 0) x[nA - 1] else 0.0
                            val vK = if (nK > 0) x[nK - 1] else 0.0
                            val vG = if (nG > 0) x[nG - 1] else 0.0

                            val vgk = vG - vK
                            val vak = vA - vK

                            val p = SpiceParameters(comp.valueStr)
                            val vgt = p.getDouble("vtrigger", p.getDouble("vgt", 0.7))
                            val vhold = p.getDouble("vholding", p.getDouble("vhold", 0.1))

                            val latched = thyristorLatched[comp.id] ?: false
                            if (!latched && vgk > vgt && vak > 0.5) {
                                thyristorLatched[comp.id] = true
                                allDevicesConverged = false
                            } else if (latched && vak < vhold) {
                                thyristorLatched[comp.id] = false
                                allDevicesConverged = false
                            }
                        }
                        ComponentType.TRIAC -> {
                            val nMT1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nMT2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val vMT1 = if (nMT1 > 0) x[nMT1 - 1] else 0.0
                            val vMT2 = if (nMT2 > 0) x[nMT2 - 1] else 0.0
                            val vG = if (nG > 0) x[nG - 1] else 0.0

                            val vg_mt1 = vG - vMT1
                            val vmt2_mt1 = vMT2 - vMT1

                            val p = SpiceParameters(comp.valueStr)
                            val vgt = p.getDouble("vtrigger", p.getDouble("vgt", 0.7))
                            val vhold = p.getDouble("vholding", p.getDouble("vhold", 0.1))

                            val latched = triacLatched[comp.id] ?: false
                            if (!latched && abs(vg_mt1) > vgt && abs(vmt2_mt1) > 0.5) {
                                triacLatched[comp.id] = true
                                allDevicesConverged = false
                            } else if (latched && abs(vmt2_mt1) < vhold) {
                                triacLatched[comp.id] = false
                                allDevicesConverged = false
                            }
                        }
                        ComponentType.OPAMP -> {
                            val nInv = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nNon = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val vInv = if (nInv > 0) x[nInv - 1] else 0.0
                            val vNon = if (nNon > 0) x[nNon - 1] else 0.0
                            val newVdiff = vNon - vInv
                            val prevVdiff = opampVoltagesDiff[comp.id] ?: 0.0
                            if (abs(newVdiff - prevVdiff) > 1e-4) {
                                allDevicesConverged = false
                            }
                            opampVoltagesDiff[comp.id] = prevVdiff + 0.5 * (newVdiff - prevVdiff)
                        }
                        else -> {}
                    }
                }

                converged = allDevicesConverged
                nrIterations++
            }

            for (nodeIdx in 0 until totalNodes) {
                val voltage = if (nodeIdx == 0) 0.0 else solvedX[nodeIdx - 1]
                outputVoltages[nodeIdx]?.add(voltage)
            }

            vSources.forEachIndexed { vIdx, vsrc ->
                val mnaVarIdx = (totalNodes - 1) + vIdx
                val current = solvedX[mnaVarIdx]
                outputCurrents[vsrc.id]?.add(current)
            }

            components.forEach { comp ->
                val cPins = comp.getPins()
                val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                val vAnode = if (nA > 0) solvedX[nA - 1] else 0.0
                val vCathode = if (nB > 0) solvedX[nB - 1] else 0.0
                val vComp = vAnode - vCathode

                when (comp.type) {
                    ComponentType.CAPACITOR -> {
                        capacitorVoltages[comp.id] = vComp
                    }
                    ComponentType.INDUCTOR -> {
                        val p = SpiceParameters(comp.valueStr)
                        val lVal = maxOf(1e-20, p.getDouble("l", p.mainValue))
                        val prevI = inductorCurrents[comp.id] ?: 0.0
                        val deltaI = (stepTime / lVal) * vComp
                        inductorCurrents[comp.id] = prevI + deltaI
                    }
                    else -> {}
                }
            }

            t += stepTime
        }

        val resultsMap = mutableMapOf<String, List<Double>>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            resultsMap[name] = outputVoltages[i] ?: emptyList()
        }

        val currentsMapped = outputCurrents.mapKeys { "I(${it.key})" }.mapValues { it.value.map { -it } }

        val nodeIndexesSolved = mutableMapOf<String, Int>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            nodeIndexesSolved[name] = i
        }

        return SimResult(
            timePoints = timePoints,
            xlabel = "Time (s)",
            nodeVoltages = resultsMap,
            currents = currentsMapped,
            nodeIndexes = nodeIndexesSolved
        )
    }

    // --- SUB-SOLVER 2: DC OPERATING POINT SOLVER (.OP) ---
    private fun runOP(
        components: List<Component>,
        settings: SimulationSettings,
        totalNodes: Int,
        mnaSize: Int,
        vSources: List<Component>,
        ptToNode: Map<GridPoint, Int>,
        nodeNames: Map<Int, String>
    ): SimResult {
        val diodeVoltages = mutableMapOf<String, Double>()
        components.filter { it.type == ComponentType.DIODE }.forEach { diodeVoltages[it.id] = 0.6 }

        val bjtVoltagesBE = mutableMapOf<String, Double>()
        val mosVoltagesGS = mutableMapOf<String, Double>()
        val mosVoltagesDS = mutableMapOf<String, Double>()
        val thyristorLatched = mutableMapOf<String, Boolean>()
        val triacLatched = mutableMapOf<String, Boolean>()
        val opampVoltagesDiff = mutableMapOf<String, Double>()

        components.filter { it.type == ComponentType.TRANSISTOR_NPN }.forEach { bjtVoltagesBE[it.id] = 0.6 }
        components.filter { it.type == ComponentType.MOSFET_N }.forEach {
            mosVoltagesGS[it.id] = 0.0
            mosVoltagesDS[it.id] = 0.0
        }
        components.filter { it.type == ComponentType.THYRISTOR }.forEach { thyristorLatched[it.id] = false }
        components.filter { it.type == ComponentType.TRIAC }.forEach { triacLatched[it.id] = false }
        components.filter { it.type == ComponentType.OPAMP }.forEach { opampVoltagesDiff[it.id] = 0.0 }

        var solvedX = DoubleArray(mnaSize)
        var converged = false
        var iterations = 0
        val maxIterations = 50

        val vSourceEvaluators = vSources.map { vsrc ->
            val vStr = vsrc.valueStr.trim().lowercase()
            val evaluator: (Double) -> Double = when {
                vStr.startsWith("sine") -> { tVal -> SpiceUtils.parseSineParams(vsrc.valueStr).evaluate(tVal) }
                vStr.startsWith("pulse") -> { tVal -> SpiceUtils.parsePulseParams(vsrc.valueStr).evaluate(tVal) }
                else -> { _ -> SpiceUtils.parseValue(vsrc.valueStr) }
            }
            vsrc.id to evaluator
        }.toMap()

        while (iterations < maxIterations && !converged) {
            val A = Array(mnaSize) { DoubleArray(mnaSize) }
            val B = DoubleArray(mnaSize)

            for (i in 0 until (totalNodes - 1)) {
                A[i][i] += 1e-12
            }

            components.forEach { comp ->
                val cPins = comp.getPins()
                val p = SpiceParameters(comp.valueStr)
                when (comp.type) {
                    ComponentType.RESISTOR -> {
                        val rVal = maxOf(1e-9, p.getDouble("r", p.mainValue))
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        stampConductance(A, nA, nB, 1.0 / rVal)
                    }
                    ComponentType.CAPACITOR -> {
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        stampConductance(A, nA, nB, 1e-15) 
                    }
                    ComponentType.INDUCTOR -> {
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        stampConductance(A, nA, nB, 1e6) 
                    }
                    ComponentType.CURRENT_SOURCE -> {
                        val iVal = p.getDouble("dc", p.mainValue)
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        stampCurrentSource(B, nA, nB, iVal)
                    }
                    ComponentType.DIODE -> {
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        
                        val Is = p.getDouble("is", 1e-14)
                        val Vt = p.getDouble("vt", 0.02585)
                        val n = p.getDouble("n", p.getDouble("ncoef", 1.0))

                        val vdPrev = diodeVoltages[comp.id] ?: 0.6
                        val vdChecked = minOf(0.85, maxOf(-10.0, vdPrev))

                        val expTerm = Math.exp(vdChecked / (n * Vt))
                        val Id = Is * (expTerm - 1.0)
                        val Gd = (Is / (n * Vt)) * expTerm

                        stampConductance(A, nA, nB, Gd)
                        val Ieq = Id - Gd * vdChecked
                        stampCurrentSource(B, nA, nB, Ieq)
                    }
                    ComponentType.TRANSISTOR_NPN -> {
                        val nB = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nC = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nE = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val Is = p.getDouble("is", 1e-14)
                        val Vt = p.getDouble("vt", 0.02585)
                        val beta = p.getDouble("bf", p.getDouble("beta", 100.0))

                        val prevVbe = bjtVoltagesBE[comp.id] ?: 0.6
                        val vbeChecked = minOf(0.85, maxOf(-10.0, prevVbe))

                        val expTerm = Math.exp(vbeChecked / Vt)
                        val Ibe = Is * (expTerm - 1.0)
                        val Gbe = (Is / Vt) * expTerm

                        stampConductance(A, nB, nE, Gbe)
                        stampCurrentSource(B, nB, nE, Ibe - Gbe * vbeChecked)

                        if (nC > 0) {
                            if (nB > 0) A[nC - 1][nB - 1] += beta * Gbe
                            if (nE > 0) A[nC - 1][nE - 1] -= beta * Gbe
                        }
                        if (nE > 0) {
                            if (nB > 0) A[nE - 1][nB - 1] -= beta * Gbe
                            if (nE > 0) A[nE - 1][nE - 1] += beta * Gbe
                        }
                        val IeqIc = beta * (Ibe - Gbe * vbeChecked)
                        stampCurrentSource(B, nC, nE, IeqIc)
                    }
                    ComponentType.MOSFET_N -> {
                        val nG = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nD = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nS = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val Vth = p.getDouble("vto", p.getDouble("vth", p.getDouble("vth_n", 2.0)))
                        val betaMos = p.getDouble("kp", p.getDouble("betamos", 1e-3))

                        val prevVgs = mosVoltagesGS[comp.id] ?: 0.0
                        val prevVds = mosVoltagesDS[comp.id] ?: 0.0

                        val Ids: Double
                        val gm: Double
                        val gds: Double

                        if (prevVgs < Vth) {
                            Ids = 0.0
                            gm = 0.0
                            gds = 1e-12
                        } else if (prevVds < prevVgs - Vth) {
                            Ids = betaMos * (2.0 * (prevVgs - Vth) * prevVds - prevVds * prevVds)
                            gm = 2.0 * betaMos * prevVds
                            gds = 2.0 * betaMos * (prevVgs - Vth - prevVds)
                        } else {
                            Ids = betaMos * (prevVgs - Vth) * (prevVgs - Vth)
                            gm = 2.0 * betaMos * (prevVgs - Vth)
                            gds = 1e-5
                        }

                        stampConductance(A, nD, nS, gds)

                        if (nD > 0) {
                            if (nG > 0) A[nD - 1][nG - 1] += gm
                            if (nS > 0) A[nD - 1][nS - 1] -= gm
                        }
                        if (nS > 0) {
                            if (nG > 0) A[nS - 1][nG - 1] -= gm
                            if (nS > 0) A[nS - 1][nS - 1] += gm
                        }

                        val Ieq = Ids - gm * prevVgs - gds * prevVds
                        stampCurrentSource(B, nD, nS, Ieq)
                    }
                    ComponentType.THYRISTOR -> {
                        val nANode = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nK = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val rGate = p.getDouble("rgate", p.getDouble("rg", 1e5))
                        val rOn = p.getDouble("ron", 1.0)
                        val rOff = p.getDouble("roff", 1e7)

                        stampConductance(A, nG, nK, 1.0 / rGate)

                        val latched = thyristorLatched[comp.id] ?: false
                        if (latched) {
                            stampConductance(A, nANode, nK, 1.0 / rOn)
                        } else {
                            stampConductance(A, nANode, nK, 1.0 / rOff)
                        }
                    }
                    ComponentType.TRIAC -> {
                        val nMT1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nMT2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val rGate = p.getDouble("rgate", p.getDouble("rg", 1e5))
                        val rOn = p.getDouble("ron", 1.0)
                        val rOff = p.getDouble("roff", 1e7)

                        stampConductance(A, nG, nMT1, 1.0 / rGate)

                        val latched = triacLatched[comp.id] ?: false
                        if (latched) {
                            stampConductance(A, nMT2, nMT1, 1.0 / rOn)
                        } else {
                            stampConductance(A, nMT2, nMT1, 1.0 / rOff)
                        }
                    }
                    ComponentType.RELAY -> {
                        val nC1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nC2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nS1 = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                        val nS2 = if (cPins.size > 3) ptToNode[cPins[3]] ?: 0 else 0

                        val rCoil = p.getDouble("rcoil", p.getDouble("rc", 100.0))
                        val rOn = p.getDouble("ron", 0.1)
                        val rOff = p.getDouble("roff", 1e7)
                        val vTrigger = p.getDouble("vtrigger", p.getDouble("vt", 3.0))

                        stampConductance(A, nC1, nC2, 1.0 / rCoil)

                        val vC1Val = if (nC1 > 0) solvedX.getOrElse(nC1 - 1) { 0.0 } else 0.0
                        val vC2Val = if (nC2 > 0) solvedX.getOrElse(nC2 - 1) { 0.0 } else 0.0
                        val coilActive = abs(vC1Val - vC2Val) > vTrigger

                        if (coilActive) {
                            stampConductance(A, nS1, nS2, 1.0 / rOn)
                        } else {
                            stampConductance(A, nS1, nS2, 1.0 / rOff)
                        }
                    }
                    ComponentType.OPAMP -> {
                        val nInv = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nNon = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nOut = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val rOut = p.getDouble("rout", p.getDouble("ro", 50.0))
                        val gain = p.getDouble("gain", p.getDouble("a", 100000.0))
                        val vMax = p.getDouble("vmax", p.getDouble("vsat", 12.0))

                        stampConductance(A, nOut, 0, 1.0 / rOut)

                        val prevVdiff = opampVoltagesDiff[comp.id] ?: 0.0
                        val Gm = gain / rOut
                        val limitI = vMax / rOut
                        val Istamp = minOf(limitI, maxOf(-limitI, Gm * prevVdiff))
                        stampCurrentSource(B, 0, nOut, Istamp)
                    }
                    else -> {}
                }
            }

            vSources.forEachIndexed { idx, vsrc ->
                val cPins = vsrc.getPins()
                val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                val valEval = vSourceEvaluators[vsrc.id]?.invoke(0.0) ?: 0.0
                stampVoltageSource(A, B, nA, nB, idx, valEval, totalNodes)
            }

            val x = solveMatrix(A, B)
            solvedX = x

            var allDevicesConverged = true
            components.forEach { comp ->
                val cPins = comp.getPins()
                when (comp.type) {
                    ComponentType.DIODE -> {
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val vAVal = if (nA > 0) x[nA - 1] else 0.0
                        val vBVal = if (nB > 0) x[nB - 1] else 0.0
                        val newVd = vAVal - vBVal
                        val prevVd = diodeVoltages[comp.id] ?: 0.6
                        if (abs(newVd - prevVd) > 1e-4) {
                            allDevicesConverged = false
                        }
                        diodeVoltages[comp.id] = prevVd + 0.5 * (newVd - prevVd)
                    }
                    ComponentType.TRANSISTOR_NPN -> {
                        val nB = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nE = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                        val vB = if (nB > 0) x[nB - 1] else 0.0
                        val vE = if (nE > 0) x[nE - 1] else 0.0
                        val newVbe = vB - vE
                        val prevVbe = bjtVoltagesBE[comp.id] ?: 0.6
                        if (abs(newVbe - prevVbe) > 1e-4) {
                            allDevicesConverged = false
                        }
                        bjtVoltagesBE[comp.id] = prevVbe + 0.5 * (newVbe - prevVbe)
                    }
                    ComponentType.MOSFET_N -> {
                        val nG = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nD = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nS = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val vG = if (nG > 0) x[nG - 1] else 0.0
                        val vD = if (nD > 0) x[nD - 1] else 0.0
                        val vS = if (nS > 0) x[nS - 1] else 0.0

                        val newVgs = vG - vS
                        val newVds = vD - vS

                        val prevVgs = mosVoltagesGS[comp.id] ?: 0.0
                        val prevVds = mosVoltagesDS[comp.id] ?: 0.0

                        if (abs(newVgs - prevVgs) > 1e-4 || abs(newVds - prevVds) > 1e-4) {
                            allDevicesConverged = false
                        }
                        mosVoltagesGS[comp.id] = prevVgs + 0.5 * (newVgs - prevVgs)
                        mosVoltagesDS[comp.id] = prevVds + 0.5 * (newVds - prevVds)
                    }
                    ComponentType.THYRISTOR -> {
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nK = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val vA = if (nA > 0) x[nA - 1] else 0.0
                        val vK = if (nK > 0) x[nK - 1] else 0.0
                        val vG = if (nG > 0) x[nG - 1] else 0.0

                        val vgk = vG - vK
                        val vak = vA - vK

                        val p = SpiceParameters(comp.valueStr)
                        val vgt = p.getDouble("vtrigger", p.getDouble("vgt", 0.7))
                        val vhold = p.getDouble("vholding", p.getDouble("vhold", 0.1))

                        val latched = thyristorLatched[comp.id] ?: false
                        if (!latched && vgk > vgt && vak > 0.5) {
                            thyristorLatched[comp.id] = true
                            allDevicesConverged = false
                        } else if (latched && vak < vhold) {
                            thyristorLatched[comp.id] = false
                            allDevicesConverged = false
                        }
                    }
                    ComponentType.TRIAC -> {
                        val nMT1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nMT2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                        val vMT1 = if (nMT1 > 0) x[nMT1 - 1] else 0.0
                        val vMT2 = if (nMT2 > 0) x[nMT2 - 1] else 0.0
                        val vG = if (nG > 0) x[nG - 1] else 0.0

                        val vg_mt1 = vG - vMT1
                        val vmt2_mt1 = vMT2 - vMT1

                        val p = SpiceParameters(comp.valueStr)
                        val vgt = p.getDouble("vtrigger", p.getDouble("vgt", 0.7))
                        val vhold = p.getDouble("vholding", p.getDouble("vhold", 0.1))

                        val latched = triacLatched[comp.id] ?: false
                        if (!latched && abs(vg_mt1) > vgt && abs(vmt2_mt1) > 0.5) {
                            triacLatched[comp.id] = true
                            allDevicesConverged = false
                        } else if (latched && abs(vmt2_mt1) < vhold) {
                            triacLatched[comp.id] = false
                            allDevicesConverged = false
                        }
                    }
                    ComponentType.OPAMP -> {
                        val nInv = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nNon = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val vInv = if (nInv > 0) x[nInv - 1] else 0.0
                        val vNon = if (nNon > 0) x[nNon - 1] else 0.0
                        val newVdiff = vNon - vInv
                        val prevVdiff = opampVoltagesDiff[comp.id] ?: 0.0
                        if (abs(newVdiff - prevVdiff) > 1e-4) {
                            allDevicesConverged = false
                        }
                        opampVoltagesDiff[comp.id] = prevVdiff + 0.5 * (newVdiff - prevVdiff)
                    }
                    else -> {}
                }
            }
            converged = allDevicesConverged
            iterations++
        }

        val timePoints = listOf(0.0, 1.0)
        val resultsMap = mutableMapOf<String, List<Double>>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            val voltage = if (i == 0) 0.0 else solvedX[i - 1]
            resultsMap[name] = listOf(voltage, voltage)
        }

        val currentsMapped = mutableMapOf<String, List<Double>>()
        vSources.forEachIndexed { vIdx, vsrc ->
            val mnaVarIdx = (totalNodes - 1) + vIdx
            val current = -solvedX[mnaVarIdx]
            currentsMapped["I(${vsrc.id})"] = listOf(current, current)
        }

        val nodeIndexesSolved = mutableMapOf<String, Int>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            nodeIndexesSolved[name] = i
        }

        return SimResult(
            timePoints = timePoints,
            xlabel = "OP",
            nodeVoltages = resultsMap,
            currents = currentsMapped,
            nodeIndexes = nodeIndexesSolved
        )
    }

    // --- SUB-SOLVER 3: COMPLEX AC SMALL SIGNAL ANALYSIS (.AC) ---
    private fun runAC(
        components: List<Component>,
        settings: SimulationSettings,
        totalNodes: Int,
        mnaSize: Int,
        vSources: List<Component>,
        ptToNode: Map<GridPoint, Int>,
        nodeNames: Map<Int, String>
    ): SimResult {
        val startFreq = maxOf(1.0, SpiceUtils.parseValue(settings.acStartFreqStr))
        val stopFreq = maxOf(startFreq * 10, SpiceUtils.parseValue(settings.acStopFreqStr))
        val numPoints = maxOf(5, settings.acPointsCount)

        val freqPoints = mutableListOf<Double>()
        val outputVoltages = mutableMapOf<Int, MutableList<Double>>()
        for (i in 0 until totalNodes) {
            outputVoltages[i] = mutableListOf()
        }
        val outputCurrents = mutableMapOf<String, MutableList<Double>>()
        vSources.forEach { outputCurrents[it.id] = mutableListOf() }

        for (step in 0 until numPoints) {
            val f = if (numPoints > 1) {
                startFreq * Math.pow(stopFreq / startFreq, step.toDouble() / (numPoints - 1))
            } else {
                startFreq
            }
            freqPoints.add(f)

            val omega = 2.0 * Math.PI * f

            // Create Complex MNA space
            val A = Array(mnaSize) { Array(mnaSize) { Complex(0.0, 0.0) } }
            val B = Array(mnaSize) { Complex(0.0, 0.0) }

            // GMIN matrix diagonal stabilizer
            for (i in 0 until (totalNodes - 1)) {
                A[i][i] = A[i][i] + Complex(1e-12, 0.0)
            }

            components.forEach { comp ->
                val cPins = comp.getPins()
                val p = SpiceParameters(comp.valueStr)
                when (comp.type) {
                    ComponentType.RESISTOR -> {
                        val rVal = maxOf(1e-9, p.getDouble("r", p.mainValue))
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        stampComplexConductance(A, nA, nB, Complex(1.0 / rVal, 0.0))
                    }
                    ComponentType.CAPACITOR -> {
                        val cVal = p.getDouble("c", p.mainValue)
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val admittance = Complex(0.0, omega * cVal)
                        stampComplexConductance(A, nA, nB, admittance)
                    }
                    ComponentType.INDUCTOR -> {
                        val lVal = maxOf(1e-20, p.getDouble("l", p.mainValue))
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val admittance = Complex(0.0, -1.0 / (omega * lVal))
                        stampComplexConductance(A, nA, nB, admittance)
                    }
                    ComponentType.CURRENT_SOURCE -> {
                        val iVal = p.getDouble("ac", p.getDouble("dc", p.mainValue))
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        stampComplexCurrentSource(B, nA, nB, Complex(iVal, 0.0))
                    }
                    ComponentType.DIODE -> {
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val Is = p.getDouble("is", 1e-14)
                        val Vt = p.getDouble("vt", 0.02585)
                        val n = p.getDouble("n", p.getDouble("ncoef", 1.0))
                        val Gd = (Is / (n * Vt)) * Math.exp(0.6 / (n * Vt))
                        stampComplexConductance(A, nA, nB, Complex(Gd, 0.0))
                    }
                    ComponentType.TRANSISTOR_NPN -> {
                        val nB = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nC = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nE = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                        val beta = p.getDouble("bf", p.getDouble("beta", 100.0))
                        val gm = p.getDouble("gm", 0.04)
                        stampComplexConductance(A, nB, nE, Complex(1.0 / (25.0 * beta), 0.0))
                        if (nC > 0) {
                            if (nB > 0) A[nC - 1][nB - 1] = A[nC - 1][nB - 1] + Complex(gm, 0.0)
                            if (nE > 0) A[nC - 1][nE - 1] = A[nC - 1][nE - 1] - Complex(gm, 0.0)
                        }
                        if (nE > 0) {
                            if (nB > 0) A[nE - 1][nB - 1] = A[nE - 1][nB - 1] - Complex(gm, 0.0)
                            if (nE > 0) A[nE - 1][nE - 1] = A[nE - 1][nE - 1] + Complex(gm, 0.0)
                        }
                    }
                    ComponentType.MOSFET_N -> {
                        val nG = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nD = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nS = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                        val gm = p.getDouble("gm", p.getDouble("kp", 0.002))
                        if (nD > 0) {
                            if (nG > 0) A[nD - 1][nG - 1] = A[nD - 1][nG - 1] + Complex(gm, 0.0)
                            if (nS > 0) A[nD - 1][nS - 1] = A[nD - 1][nS - 1] - Complex(gm, 0.0)
                        }
                        if (nS > 0) {
                            if (nG > 0) A[nS - 1][nG - 1] = A[nS - 1][nG - 1] - Complex(gm, 0.0)
                            if (nS > 0) A[nS - 1][nS - 1] = A[nS - 1][nS - 1] + Complex(gm, 0.0)
                        }
                    }
                    ComponentType.THYRISTOR, ComponentType.TRIAC -> {
                        val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        stampComplexConductance(A, nA, nB, Complex(1e-6, 0.0))
                    }
                    ComponentType.RELAY -> {
                        val nCol1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nCol2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val rCoil = p.getDouble("rcoil", p.getDouble("rc", 100.0))
                        stampComplexConductance(A, nCol1, nCol2, Complex(1.0 / rCoil, 0.0))
                    }
                    ComponentType.OPAMP -> {
                        val nInv = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                        val nNon = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                        val nOut = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                        val rOut = p.getDouble("rout", p.getDouble("ro", 50.0))
                        val gain = p.getDouble("gain", p.getDouble("a", 100000.0))
                        val gm = gain / rOut
                        stampComplexConductance(A, nOut, 0, Complex(1.0 / rOut, 0.0))
                        if (nOut > 0) {
                            if (nNon > 0) A[nOut - 1][nNon - 1] = A[nOut - 1][nNon - 1] + Complex(gm, 0.0)
                            if (nInv > 0) A[nOut - 1][nInv - 1] = A[nOut - 1][nInv - 1] - Complex(gm, 0.0)
                        }
                    }
                    else -> {}
                }
            }

            vSources.forEachIndexed { idx, vsrc ->
                val cPins = vsrc.getPins()
                val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                stampComplexVoltageSource(A, B, nA, nB, idx, Complex(1.0, 0.0), totalNodes)
            }

            val xComplex = solveComplexMatrix(A, B)

            for (nodeIdx in 0 until totalNodes) {
                val value = if (nodeIdx == 0) Complex(0.0, 0.0) else xComplex[nodeIdx - 1]
                outputVoltages[nodeIdx]?.add(value.magnitude())
            }

            vSources.forEachIndexed { vIdx, vsrc ->
                val mnaVarIdx = (totalNodes - 1) + vIdx
                val currentComp = xComplex[mnaVarIdx]
                outputCurrents[vsrc.id]?.add(currentComp.magnitude())
            }
        }

        val resultsMap = mutableMapOf<String, List<Double>>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            resultsMap[name] = outputVoltages[i] ?: emptyList()
        }

        val currentsMapped = outputCurrents.mapKeys { "I(${it.key})" }.mapValues { it.value }

        val nodeIndexesSolved = mutableMapOf<String, Int>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            nodeIndexesSolved[name] = i
        }

        return SimResult(
            timePoints = freqPoints,
            xlabel = "Frequency (Hz)",
            nodeVoltages = resultsMap,
            currents = currentsMapped,
            nodeIndexes = nodeIndexesSolved
        )
    }

    // --- SUB-SOLVER 4: DC SWEEP SOLVER (.DC) ---
    private fun runDCSweep(
        components: List<Component>,
        settings: SimulationSettings,
        totalNodes: Int,
        mnaSize: Int,
        vSources: List<Component>,
        ptToNode: Map<GridPoint, Int>,
        nodeNames: Map<Int, String>
    ): SimResult {
        val sweepSrcId = settings.sweepSource
        val startVal = settings.sweepStart
        val stopVal = settings.sweepStop
        val stepVal = maxOf(0.01, settings.sweepStep)

        val sweepPoints = mutableListOf<Double>()
        val outputVoltages = mutableMapOf<Int, MutableList<Double>>()
        for (i in 0 until totalNodes) {
            outputVoltages[i] = mutableListOf()
        }
        val outputCurrents = mutableMapOf<String, MutableList<Double>>()
        vSources.forEach { outputCurrents[it.id] = mutableListOf() }

        var currentVal = startVal
        val maxSweepSteps = 500
        var count = 0

        val diodeVoltages = mutableMapOf<String, Double>()
        components.filter { it.type == ComponentType.DIODE }.forEach { diodeVoltages[it.id] = 0.6 }

        val bjtVoltagesBE = mutableMapOf<String, Double>()
        val mosVoltagesGS = mutableMapOf<String, Double>()
        val mosVoltagesDS = mutableMapOf<String, Double>()
        val thyristorLatched = mutableMapOf<String, Boolean>()
        val triacLatched = mutableMapOf<String, Boolean>()
        val opampVoltagesDiff = mutableMapOf<String, Double>()

        components.filter { it.type == ComponentType.TRANSISTOR_NPN }.forEach { bjtVoltagesBE[it.id] = 0.6 }
        components.filter { it.type == ComponentType.MOSFET_N }.forEach {
            mosVoltagesGS[it.id] = 0.0
            mosVoltagesDS[it.id] = 0.0
        }
        components.filter { it.type == ComponentType.THYRISTOR }.forEach { thyristorLatched[it.id] = false }
        components.filter { it.type == ComponentType.TRIAC }.forEach { triacLatched[it.id] = false }
        components.filter { it.type == ComponentType.OPAMP }.forEach { opampVoltagesDiff[it.id] = 0.0 }

        while (currentVal <= stopVal && count < maxSweepSteps) {
            sweepPoints.add(currentVal)

            var solvedX = DoubleArray(mnaSize)
            var converged = false
            var nrIterations = 0
            val maxNrIterations = 20

            while (nrIterations < maxNrIterations && !converged) {
                val A = Array(mnaSize) { DoubleArray(mnaSize) }
                val B = DoubleArray(mnaSize)

                for (i in 0 until (totalNodes - 1)) {
                    A[i][i] += 1e-12
                }

                components.forEach { comp ->
                    val cPins = comp.getPins()
                    val p = SpiceParameters(comp.valueStr)
                    when (comp.type) {
                        ComponentType.RESISTOR -> {
                            val rVal = maxOf(1e-9, p.getDouble("r", p.mainValue))
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            stampConductance(A, nA, nB, 1.0 / rVal)
                        }
                        ComponentType.CAPACITOR -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            stampConductance(A, nA, nB, 1e-15)
                        }
                        ComponentType.INDUCTOR -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            stampConductance(A, nA, nB, 1e6)
                        }
                        ComponentType.CURRENT_SOURCE -> {
                            val iVal = p.getDouble("dc", p.mainValue)
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            stampCurrentSource(B, nA, nB, iVal)
                        }
                        ComponentType.DIODE -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            
                            val Is = p.getDouble("is", 1e-14)
                            val Vt = p.getDouble("vt", 0.02585)
                            val n = p.getDouble("n", p.getDouble("ncoef", 1.0))

                            val vdPrev = diodeVoltages[comp.id] ?: 0.6
                            val vdChecked = minOf(0.85, maxOf(-10.0, vdPrev))

                            val expTerm = Math.exp(vdChecked / (n * Vt))
                            val Id = Is * (expTerm - 1.0)
                            val Gd = (Is / (n * Vt)) * expTerm

                            stampConductance(A, nA, nB, Gd)
                            val Ieq = Id - Gd * vdChecked
                            stampCurrentSource(B, nA, nB, Ieq)
                        }
                        ComponentType.TRANSISTOR_NPN -> {
                            val nB = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nC = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nE = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val Is = p.getDouble("is", 1e-14)
                            val Vt = p.getDouble("vt", 0.02585)
                            val beta = p.getDouble("bf", p.getDouble("beta", 100.0))

                            val prevVbe = bjtVoltagesBE[comp.id] ?: 0.6
                            val vbeChecked = minOf(0.85, maxOf(-10.0, prevVbe))

                            val expTerm = Math.exp(vbeChecked / Vt)
                            val Ibe = Is * (expTerm - 1.0)
                            val Gbe = (Is / Vt) * expTerm

                            stampConductance(A, nB, nE, Gbe)
                            stampCurrentSource(B, nB, nE, Ibe - Gbe * vbeChecked)

                            if (nC > 0) {
                                if (nB > 0) A[nC - 1][nB - 1] += beta * Gbe
                                if (nE > 0) A[nC - 1][nE - 1] -= beta * Gbe
                            }
                            if (nE > 0) {
                                if (nB > 0) A[nE - 1][nB - 1] -= beta * Gbe
                                if (nE > 0) A[nE - 1][nE - 1] += beta * Gbe
                            }
                            val IeqIc = beta * (Ibe - Gbe * vbeChecked)
                            stampCurrentSource(B, nC, nE, IeqIc)
                        }
                        ComponentType.MOSFET_N -> {
                            val nG = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nD = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nS = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val Vth = p.getDouble("vto", p.getDouble("vth", p.getDouble("vth_n", 2.0)))
                            val betaMos = p.getDouble("kp", p.getDouble("betamos", 1e-3))

                            val prevVgs = mosVoltagesGS[comp.id] ?: 0.0
                            val prevVds = mosVoltagesDS[comp.id] ?: 0.0

                            val Ids: Double
                            val gm: Double
                            val gds: Double

                            if (prevVgs < Vth) {
                                Ids = 0.0
                                gm = 0.0
                                gds = 1e-12
                            } else if (prevVds < prevVgs - Vth) {
                                Ids = betaMos * (2.0 * (prevVgs - Vth) * prevVds - prevVds * prevVds)
                                gm = 2.0 * betaMos * prevVds
                                gds = 2.0 * betaMos * (prevVgs - Vth - prevVds)
                            } else {
                                Ids = betaMos * (prevVgs - Vth) * (prevVgs - Vth)
                                gm = 2.0 * betaMos * (prevVgs - Vth)
                                gds = 1e-5
                            }

                            stampConductance(A, nD, nS, gds)

                            if (nD > 0) {
                                if (nG > 0) A[nD - 1][nG - 1] += gm
                                if (nS > 0) A[nD - 1][nS - 1] -= gm
                            }
                            if (nS > 0) {
                                if (nG > 0) A[nS - 1][nG - 1] -= gm
                                if (nS > 0) A[nS - 1][nS - 1] += gm
                            }

                            val Ieq = Ids - gm * prevVgs - gds * prevVds
                            stampCurrentSource(B, nD, nS, Ieq)
                        }
                        ComponentType.THYRISTOR -> {
                            val nANode = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nK = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val rGate = p.getDouble("rgate", p.getDouble("rg", 1e5))
                            val rOn = p.getDouble("ron", 1.0)
                            val rOff = p.getDouble("roff", 1e7)

                            stampConductance(A, nG, nK, 1.0 / rGate)

                            val latched = thyristorLatched[comp.id] ?: false
                            if (latched) {
                                stampConductance(A, nANode, nK, 1.0 / rOn)
                            } else {
                                stampConductance(A, nANode, nK, 1.0 / rOff)
                            }
                        }
                        ComponentType.TRIAC -> {
                            val nMT1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nMT2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val rGate = p.getDouble("rgate", p.getDouble("rg", 1e5))
                            val rOn = p.getDouble("ron", 1.0)
                            val rOff = p.getDouble("roff", 1e7)

                            stampConductance(A, nG, nMT1, 1.0 / rGate)

                            val latched = triacLatched[comp.id] ?: false
                            if (latched) {
                                stampConductance(A, nMT2, nMT1, 1.0 / rOn)
                            } else {
                                stampConductance(A, nMT2, nMT1, 1.0 / rOff)
                            }
                        }
                        ComponentType.RELAY -> {
                            val nC1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nC2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nS1 = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                            val nS2 = if (cPins.size > 3) ptToNode[cPins[3]] ?: 0 else 0

                            val rCoil = p.getDouble("rcoil", p.getDouble("rc", 100.0))
                            val rOn = p.getDouble("ron", 0.1)
                            val rOff = p.getDouble("roff", 1e7)
                            val vTrigger = p.getDouble("vtrigger", p.getDouble("vt", 3.0))

                            stampConductance(A, nC1, nC2, 1.0 / rCoil)

                            val vC1Val = if (nC1 > 0) solvedX.getOrElse(nC1 - 1) { 0.0 } else 0.0
                            val vC2Val = if (nC2 > 0) solvedX.getOrElse(nC2 - 1) { 0.0 } else 0.0
                            val coilActive = abs(vC1Val - vC2Val) > vTrigger

                            if (coilActive) {
                                stampConductance(A, nS1, nS2, 1.0 / rOn)
                            } else {
                                stampConductance(A, nS1, nS2, 1.0 / rOff)
                            }
                        }
                        ComponentType.OPAMP -> {
                            val nInv = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nNon = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nOut = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val rOut = p.getDouble("rout", p.getDouble("ro", 50.0))
                            val gain = p.getDouble("gain", p.getDouble("a", 100000.0))
                            val vMax = p.getDouble("vmax", p.getDouble("vsat", 12.0))

                            stampConductance(A, nOut, 0, 1.0 / rOut)

                            val prevVdiff = opampVoltagesDiff[comp.id] ?: 0.0
                            val Gm = gain / rOut
                            val limitI = vMax / rOut
                            val Istamp = minOf(limitI, maxOf(-limitI, Gm * prevVdiff))
                            stampCurrentSource(B, 0, nOut, Istamp)
                        }
                        else -> {}
                    }
                }

                vSources.forEachIndexed { idx, vsrc ->
                    val cPins = vsrc.getPins()
                    val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                    val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                    
                    val vVal = if (vsrc.id == sweepSrcId) {
                        currentVal
                    } else {
                        val vStr = vsrc.valueStr.trim().lowercase()
                        val evaluator: (Double) -> Double = when {
                            vStr.startsWith("sine") -> { tVal -> SpiceUtils.parseSineParams(vsrc.valueStr).evaluate(tVal) }
                            vStr.startsWith("pulse") -> { tVal -> SpiceUtils.parsePulseParams(vsrc.valueStr).evaluate(tVal) }
                            else -> { _ -> SpiceUtils.parseValue(vsrc.valueStr) }
                        }
                        evaluator(0.0)
                    }
                    stampVoltageSource(A, B, nA, nB, idx, vVal, totalNodes)
                }

                val x = solveMatrix(A, B)
                solvedX = x

                var allDevicesConverged = true
                components.forEach { comp ->
                    val cPins = comp.getPins()
                    when (comp.type) {
                        ComponentType.DIODE -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nB = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val vAStrVal = if (nA > 0) x[nA - 1] else 0.0
                            val vBStrVal = if (nB > 0) x[nB - 1] else 0.0
                            val newVd = vAStrVal - vBStrVal
                            val prevVd = diodeVoltages[comp.id] ?: 0.6
                            if (abs(newVd - prevVd) > 1e-4) {
                                allDevicesConverged = false
                            }
                            diodeVoltages[comp.id] = prevVd + 0.5 * (newVd - prevVd)
                        }
                        ComponentType.TRANSISTOR_NPN -> {
                            val nB = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nE = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0
                            val vB = if (nB > 0) x[nB - 1] else 0.0
                            val vE = if (nE > 0) x[nE - 1] else 0.0
                            val newVbe = vB - vE
                            val prevVbe = bjtVoltagesBE[comp.id] ?: 0.6
                            if (abs(newVbe - prevVbe) > 1e-4) {
                                allDevicesConverged = false
                            }
                            bjtVoltagesBE[comp.id] = prevVbe + 0.5 * (newVbe - prevVbe)
                        }
                        ComponentType.MOSFET_N -> {
                            val nG = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nD = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nS = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val vG = if (nG > 0) x[nG - 1] else 0.0
                            val vD = if (nD > 0) x[nD - 1] else 0.0
                            val vS = if (nS > 0) x[nS - 1] else 0.0

                            val newVgs = vG - vS
                            val newVds = vD - vS

                            val prevVgs = mosVoltagesGS[comp.id] ?: 0.0
                            val prevVds = mosVoltagesDS[comp.id] ?: 0.0

                            if (abs(newVgs - prevVgs) > 1e-4 || abs(newVds - prevVds) > 1e-4) {
                                allDevicesConverged = false
                            }
                            mosVoltagesGS[comp.id] = prevVgs + 0.5 * (newVgs - prevVgs)
                            mosVoltagesDS[comp.id] = prevVds + 0.5 * (newVds - prevVds)
                        }
                        ComponentType.THYRISTOR -> {
                            val nA = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nK = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val vA = if (nA > 0) x[nA - 1] else 0.0
                            val vK = if (nK > 0) x[nK - 1] else 0.0
                            val vG = if (nG > 0) x[nG - 1] else 0.0

                            val vgk = vG - vK
                            val vak = vA - vK

                            val p = SpiceParameters(comp.valueStr)
                            val vgt = p.getDouble("vtrigger", p.getDouble("vgt", 0.7))
                            val vhold = p.getDouble("vholding", p.getDouble("vhold", 0.1))

                            val latched = thyristorLatched[comp.id] ?: false
                            if (!latched && vgk > vgt && vak > 0.5) {
                                thyristorLatched[comp.id] = true
                                allDevicesConverged = false
                            } else if (latched && vak < vhold) {
                                thyristorLatched[comp.id] = false
                                allDevicesConverged = false
                            }
                        }
                        ComponentType.TRIAC -> {
                            val nMT1 = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nMT2 = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val nG = if (cPins.size > 2) ptToNode[cPins[2]] ?: 0 else 0

                            val vMT1 = if (nMT1 > 0) x[nMT1 - 1] else 0.0
                            val vMT2 = if (nMT2 > 0) x[nMT2 - 1] else 0.0
                            val vG = if (nG > 0) x[nG - 1] else 0.0

                            val vg_mt1 = vG - vMT1
                            val vmt2_mt1 = vMT2 - vMT1

                            val p = SpiceParameters(comp.valueStr)
                            val vgt = p.getDouble("vtrigger", p.getDouble("vgt", 0.7))
                            val vhold = p.getDouble("vholding", p.getDouble("vhold", 0.1))

                            val latched = triacLatched[comp.id] ?: false
                            if (!latched && abs(vg_mt1) > vgt && abs(vmt2_mt1) > 0.5) {
                                triacLatched[comp.id] = true
                                allDevicesConverged = false
                            } else if (latched && abs(vmt2_mt1) < vhold) {
                                triacLatched[comp.id] = false
                                allDevicesConverged = false
                            }
                        }
                        ComponentType.OPAMP -> {
                            val nInv = if (cPins.isNotEmpty()) ptToNode[cPins[0]] ?: 0 else 0
                            val nNon = if (cPins.size > 1) ptToNode[cPins[1]] ?: 0 else 0
                            val vInv = if (nInv > 0) x[nInv - 1] else 0.0
                            val vNon = if (nNon > 0) x[nNon - 1] else 0.0
                            val newVdiff = vNon - vInv
                            val prevVdiff = opampVoltagesDiff[comp.id] ?: 0.0
                            if (abs(newVdiff - prevVdiff) > 1e-4) {
                                allDevicesConverged = false
                            }
                            opampVoltagesDiff[comp.id] = prevVdiff + 0.5 * (newVdiff - prevVdiff)
                        }
                        else -> {}
                    }
                }
                converged = allDevicesConverged
                nrIterations++
            }

            for (nodeIdx in 0 until totalNodes) {
                val voltage = if (nodeIdx == 0) 0.0 else solvedX[nodeIdx - 1]
                outputVoltages[nodeIdx]?.add(voltage)
            }

            vSources.forEachIndexed { vIdx, vsrc ->
                val mnaVarIdx = (totalNodes - 1) + vIdx
                val current = solvedX[mnaVarIdx]
                outputCurrents[vsrc.id]?.add(current)
            }

            currentVal += stepVal
            count++
        }

        val resultsMap = mutableMapOf<String, List<Double>>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            resultsMap[name] = outputVoltages[i] ?: emptyList()
        }

        val currentsMapped = outputCurrents.mapKeys { "I(${it.key})" }.mapValues { it.value.map { -it } }

        val nodeIndexesSolved = mutableMapOf<String, Int>()
        for (i in 0 until totalNodes) {
            val name = nodeNames[i] ?: "N$i"
            nodeIndexesSolved[name] = i
        }

        return SimResult(
            timePoints = sweepPoints,
            xlabel = "Sweep ($sweepSrcId) [V]",
            nodeVoltages = resultsMap,
            currents = currentsMapped,
            nodeIndexes = nodeIndexesSolved
        )
    }

    // --- STAMPING HELPERS (Linear Real MNA) ---
    private fun stampConductance(A: Array<DoubleArray>, nA: Int, nB: Int, G: Double) {
        if (nA > 0) {
            A[nA - 1][nA - 1] += G
        }
        if (nB > 0) {
            A[nB - 1][nB - 1] += G
        }
        if (nA > 0 && nB > 0) {
            A[nA - 1][nB - 1] -= G
            A[nB - 1][nA - 1] -= G
        }
    }

    private fun stampCurrentSource(B: DoubleArray, nA: Int, nB: Int, Ival: Double) {
        if (nA > 0) {
            B[nA - 1] -= Ival
        }
        if (nB > 0) {
            B[nB - 1] += Ival
        }
    }

    private fun stampVoltageSource(
        A: Array<DoubleArray>,
        B: DoubleArray,
        nA: Int,
        nB: Int,
        vIdx: Int,
        vVal: Double,
        numNodes: Int
    ) {
        val srcRow = (numNodes - 1) + vIdx
        if (nA > 0) {
            A[srcRow][nA - 1] += 1.0 
            A[nA - 1][srcRow] += 1.0 
        }
        if (nB > 0) {
            A[srcRow][nB - 1] -= 1.0 
            A[nB - 1][srcRow] -= 1.0 
        }
        B[srcRow] = vVal
    }

    // --- STAMPING HELPERS (Complex MNA) ---
    private fun stampComplexConductance(A: Array<Array<Complex>>, nA: Int, nB: Int, G: Complex) {
        if (nA > 0) {
            A[nA - 1][nA - 1] = A[nA - 1][nA - 1] + G
        }
        if (nB > 0) {
            A[nB - 1][nB - 1] = A[nB - 1][nB - 1] + G
        }
        if (nA > 0 && nB > 0) {
            A[nA - 1][nB - 1] = A[nA - 1][nB - 1] - G
            A[nB - 1][nA - 1] = A[nB - 1][nA - 1] - G
        }
    }

    private fun stampComplexCurrentSource(B: Array<Complex>, nA: Int, nB: Int, Ival: Complex) {
        if (nA > 0) {
            B[nA - 1] = B[nA - 1] - Ival
        }
        if (nB > 0) {
            B[nB - 1] = B[nB - 1] + Ival
        }
    }

    private fun stampComplexVoltageSource(
        A: Array<Array<Complex>>,
        B: Array<Complex>,
        nA: Int,
        nB: Int,
        vIdx: Int,
        vVal: Complex,
        numNodes: Int
    ) {
        val srcRow = (numNodes - 1) + vIdx
        if (nA > 0) {
            A[srcRow][nA - 1] = A[srcRow][nA - 1] + Complex(1.0, 0.0)
            A[nA - 1][srcRow] = A[nA - 1][srcRow] + Complex(1.0, 0.0)
        }
        if (nB > 0) {
            A[srcRow][nB - 1] = A[srcRow][nB - 1] - Complex(1.0, 0.0)
            A[nB - 1][srcRow] = A[nB - 1][srcRow] - Complex(1.0, 0.0)
        }
        B[srcRow] = vVal
    }

    // --- MATRIX EQUATION SOLVERS ---
    private fun solveMatrix(A: Array<DoubleArray>, B: DoubleArray): DoubleArray {
        val n = B.size
        val a = Array(n) { i -> A[i].clone() }
        val b = B.clone()

        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = abs(a[col][col])
            for (row in col + 1 until n) {
                val absVal = abs(a[row][col])
                if (absVal > maxVal) {
                    maxVal = absVal
                    pivotRow = row
                }
            }

            if (pivotRow != col) {
                val tempRow = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = tempRow

                val tempVal = b[col]
                b[col] = b[pivotRow]
                b[pivotRow] = tempVal
            }

            val pivotEntry = a[col][col]
            if (abs(pivotEntry) < 1e-15) {
                a[col][col] = 1e-15
            }

            for (row in col + 1 until n) {
                val factor = a[row][col] / a[col][col]
                b[row] -= factor * b[col]
                for (c in col until n) {
                    a[row][c] -= factor * a[col][c]
                }
            }
        }

        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = 0.0
            for (j in i + 1 until n) {
                sum += a[i][j] * x[j]
            }
            x[i] = (b[i] - sum) / a[i][i]
        }

        return x
    }

    private fun solveComplexMatrix(A: Array<Array<Complex>>, B: Array<Complex>): Array<Complex> {
        val n = B.size
        val a = Array(n) { i -> A[i].clone() }
        val b = B.clone()

        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = a[col][col].magnitude()
            for (row in col + 1 until n) {
                val mag = a[row][col].magnitude()
                if (mag > maxVal) {
                    maxVal = mag
                    pivotRow = row
                }
            }

            if (pivotRow != col) {
                val tempRow = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = tempRow

                val tempVal = b[col]
                b[col] = b[pivotRow]
                b[pivotRow] = tempVal
            }

            var pivotEntry = a[col][col]
            if (pivotEntry.magnitude() < 1e-15) {
                a[col][col] = Complex(1e-15, 0.0)
                pivotEntry = a[col][col]
            }

            for (row in col + 1 until n) {
                val factor = a[row][col] / pivotEntry
                b[row] = b[row] - factor * b[col]
                for (c in col until n) {
                    a[row][c] = a[row][c] - factor * a[col][c]
                }
            }
        }

        val x = Array(n) { Complex(0.0, 0.0) }
        for (i in n - 1 downTo 0) {
            var sum = Complex(0.0, 0.0)
            for (j in i + 1 until n) {
                sum = sum + a[i][j] * x[j]
            }
            val pivot = a[i][i]
            x[i] = (b[i] - sum) / if (pivot.magnitude() == 0.0) Complex(1e-15, 0.0) else pivot
        }

        return x
    }

    companion object {
        fun flatten(
            components: List<Component>,
            wires: List<Wire>,
            prefix: String = ""
        ): Pair<List<Component>, List<Wire>> {
            val flatComps = mutableListOf<Component>()
            val flatWires = mutableListOf<Wire>()

            for (comp in components) {
                if (comp.type == ComponentType.SUBCIRCUIT) {
                    val template = SubcircuitRegistry.templates[comp.valueStr]
                    if (template != null) {
                        val subPrefix = if (prefix.isEmpty()) "${comp.id}_" else "${prefix}${comp.id}_"
                        val (subComps, subWires) = flatten(template.components, template.wires, subPrefix)

                        val outerPins = comp.getPins()
                        val localToGlobalPinMap = mutableMapOf<GridPoint, GridPoint>()
                        template.ports.forEachIndexed { idx, localPt ->
                            if (idx < outerPins.size) {
                                val globalPt = outerPins[idx]
                                localToGlobalPinMap[localPt] = globalPt
                            }
                        }

                        fun translateLocal(pt: GridPoint): GridPoint {
                            val exactMap = localToGlobalPinMap[pt]
                            if (exactMap != null) return exactMap
                            return comp.translateLocalToGlobal(pt.x, pt.y)
                        }

                        subComps.forEach { subC ->
                            val globPt = comp.translateLocalToGlobal(subC.gridX, subC.gridY)
                            val combinedOrientation = Orientation.values()[
                                (subC.orientation.ordinal + comp.orientation.ordinal) % Orientation.values().size
                            ]
                            flatComps.add(
                                subC.copy(
                                    id = subPrefix + subC.id,
                                    name = subC.name,
                                    gridX = globPt.x,
                                    gridY = globPt.y,
                                    orientation = combinedOrientation
                                )
                            )
                        }

                        subWires.forEach { subW ->
                            val startGlob = translateLocal(subW.start)
                            val endGlob = translateLocal(subW.end)
                            flatWires.add(
                                subW.copy(
                                    id = subPrefix + subW.id,
                                    start = startGlob,
                                    end = endGlob
                                )
                            )
                        }
                    } else {
                        flatComps.add(comp)
                    }
                } else {
                    if (prefix.isNotEmpty()) {
                        flatComps.add(
                            comp.copy(
                                id = prefix + comp.id,
                                name = comp.name,
                                gridX = comp.gridX,
                                gridY = comp.gridY
                            )
                        )
                    } else {
                        flatComps.add(comp)
                    }
                }
            }

            for (wire in wires) {
                if (prefix.isNotEmpty()) {
                    flatWires.add(
                        wire.copy(
                            id = prefix + wire.id
                        )
                    )
                } else {
                    flatWires.add(wire)
                }
            }

            return Pair(flatComps, flatWires)
        }
    }
}
