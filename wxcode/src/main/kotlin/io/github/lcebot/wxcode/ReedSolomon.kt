package io.github.lcebot.wxcode

/** GF(256) with the QR primitive polynomial x^8 + x^4 + x^3 + x^2 + 1. */
internal object Gf256 {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun pow(i: Int): Int = exp[i % 255]

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun div(a: Int, b: Int): Int = if (a == 0) 0 else exp[log[a] + 255 - log[b]]

    fun inv(a: Int): Int = exp[255 - log[a]]

    /** p(x) with p[0] the highest-degree coefficient, by Horner's rule. */
    fun evalDescending(p: IntArray, x: Int, size: Int = p.size): Int {
        if (x == 0) return p[size - 1]
        val lx = log[x]
        var r = 0
        for (i in 0 until size) r = (if (r == 0) 0 else exp[log[r] + lx]) xor p[i]
        return r
    }

    /** p(x) with p[i] the coefficient of x^i, for the first [size] coefficients. */
    fun evalAscending(p: IntArray, x: Int, size: Int = p.size): Int {
        var r = 0
        for (i in size - 1 downTo 0) r = mul(r, x) xor p[i]
        return r
    }
}

/**
 * Reed-Solomon over [Gf256] with generator roots alpha^0 .. alpha^(n-1), as WX_CODE uses; standard
 * QR starts at alpha^0 as well. A block is its data codewords followed by its EC codewords.
 */
internal object ReedSolomon {
    private val generators = HashMap<Int, IntArray>()

    /** The generator polynomial, highest degree first with a leading 1. */
    private fun generator(ecCount: Int): IntArray = synchronized(generators) {
        generators.getOrPut(ecCount) {
            var g = intArrayOf(1)
            for (i in 0 until ecCount) {
                val next = IntArray(g.size + 1)
                val root = Gf256.pow(i)
                for (j in g.indices) {
                    next[j] = next[j] xor g[j]
                    next[j + 1] = next[j + 1] xor Gf256.mul(g[j], root)
                }
                g = next
            }
            g
        }
    }

    fun encode(data: IntArray, ecCount: Int): IntArray {
        val g = generator(ecCount)
        val remainder = IntArray(ecCount)
        for (d in data) {
            val factor = d xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, ecCount - 1)
            remainder[ecCount - 1] = 0
            if (factor != 0) for (j in 0 until ecCount) remainder[j] = remainder[j] xor Gf256.mul(g[j + 1], factor)
        }
        return remainder
    }

    /** Corrects [block] in place; false when it holds more errors than [ecCount] can fix. */
    fun correct(block: IntArray, ecCount: Int): Boolean {
        val syndromes = IntArray(ecCount) { Gf256.evalDescending(block, Gf256.pow(it)) }
        if (syndromes.all { it == 0 }) return true

        val locator = errorLocator(syndromes) ?: return false
        val errors = locator.degree
        val n = block.size

        // Chien search: position i holds an error when Lambda(X^-1) = 0, with X = alpha^(n-1-i).
        val positions = IntArray(errors)
        var found = 0
        for (i in 0 until n) {
            val xInv = Gf256.pow(255 - (n - 1 - i) % 255)
            if (Gf256.evalAscending(locator.coefficients, xInv, errors + 1) == 0) {
                if (found == errors) return false
                positions[found++] = i
            }
        }
        if (found != errors) return false

        // Forney with first root alpha^0: e = X * Omega(X^-1) / Lambda'(X^-1).
        val omega = IntArray(ecCount)
        for (i in 0 until ecCount) {
            var v = 0
            for (j in 0..minOf(i, errors)) v = v xor Gf256.mul(syndromes[i - j], locator.coefficients[j])
            omega[i] = v
        }
        for (i in positions) {
            val power = (n - 1 - i) % 255
            val x = Gf256.pow(power)
            val xInv = Gf256.pow(255 - power)
            var derivative = 0
            var xPower = 1
            val xInvSquared = Gf256.mul(xInv, xInv)
            // Only odd terms survive the formal derivative in characteristic 2.
            for (k in 1..errors step 2) {
                derivative = derivative xor Gf256.mul(locator.coefficients[k], xPower)
                xPower = Gf256.mul(xPower, xInvSquared)
            }
            if (derivative == 0) return false
            val magnitude = Gf256.mul(x, Gf256.div(Gf256.evalAscending(omega, xInv), derivative))
            block[i] = block[i] xor magnitude
        }
        return (0 until ecCount).all { Gf256.evalDescending(block, Gf256.pow(it)) == 0 }
    }

    private class Locator(val coefficients: IntArray, val degree: Int)

    /** Berlekamp-Massey; the locator's coefficients are in ascending order with Lambda(0) = 1. */
    private fun errorLocator(s: IntArray): Locator? {
        val n = s.size
        var lambda = IntArray(n + 1).also { it[0] = 1 }
        var previous = IntArray(n + 1).also { it[0] = 1 }
        var degree = 0
        var shift = 1
        var lastDiscrepancy = 1
        for (r in 0 until n) {
            var d = s[r]
            for (i in 1..degree) d = d xor Gf256.mul(lambda[i], s[r - i])
            if (d == 0) {
                shift++
                continue
            }
            val factor = Gf256.div(d, lastDiscrepancy)
            val before = lambda.copyOf()
            for (i in 0..n - shift) lambda[i + shift] = lambda[i + shift] xor Gf256.mul(factor, previous[i])
            if (2 * degree <= r) {
                degree = r + 1 - degree
                previous = before
                lastDiscrepancy = d
                shift = 1
            } else {
                shift++
            }
        }
        if (2 * degree > n) return null
        return Locator(lambda, degree)
    }
}
