package com.animeav1.data

import java.util.concurrent.atomic.AtomicLong

/**
 * La prueba de trabajo de Byse (`assets/pow-DEJGtdh2.js`), mitad PURA para poder testearla.
 *
 * ⚠️ El servidor la anuncia como `"sha256-leading-zero-bits"` y NO es SHA-256: solo toma prestadas
 * las 4 primeras palabras del IV de SHA-256. Es una mezcla ARX propia —el cuarto de ronda de
 * ChaCha— sobre un registro de 4 palabras y un búfer de 512 (2 KiB). Hay que encontrar un contador
 * `s` tal que el hash de `latin1("<nonce>:<s>")` empiece por al menos `difficulty` bits a cero.
 * Vale CUALQUIER `s` que cumpla, no solo el menor (comprobado contra el servidor), así que la
 * búsqueda se reparte entre hilos sin coordinarse.
 *
 * Portado de un port a Java validado bit a bit contra el JS original (1210 vectores) y aceptado por
 * el servidor. Si Byse cambia la función, `verify` contesta `{"status":"error","reason":"pow_failed"}`
 * con HTTP 200: eso es lo que hay que mirar primero.
 *
 * Coste: ~2^difficulty hashes de media (geométrica: el p99 es 4,6 veces la media). Con la dificultad
 * normal (12 con un User-Agent Android, 16 con uno de escritorio) son milisegundos; cada 30 min, porque
 * el token que se gana vale para todos los vídeos (ver `AnimeRepository.resolveByse`).
 */
internal object BysePow {

    private const val N = 512
    private const val MASK = N - 1
    private const val MIX_ROUNDS = 2
    private const val LR = 0x9E3779B1.toInt()   // 2654435761
    private const val HR = 0x85EBCA77.toInt()   // 2246822519
    /** Las 4 primeras palabras del IV de SHA-256: lo único que tiene de SHA-256. */
    private val IV = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt())

    /** Hash completo (8 palabras) de [input] tal cual lo calcula el JS. Para tests y referencia. */
    fun hash(input: ByteArray): IntArray {
        val e = IV.copyOf()
        for (b in input) absorb(e, b.toInt() and 0xFF)
        return finish(e, IntArray(N), wholeOutput = true).second
    }

    fun leadingZeroBits(words: IntArray): Int {
        var z = 0
        for (w in words) {
            if (w == 0) { z += 32; continue }
            return z + Integer.numberOfLeadingZeros(w)
        }
        return z
    }

    /** Como `yr()` del JS: cada carácter a un byte con `charCode & 255`. */
    fun latin1(s: String): ByteArray = ByteArray(s.length) { (s[it].code and 0xFF).toByte() }

    /**
     * Busca una solución con [threads] hilos: el hilo k prueba k, k+T, k+2T… y gana el primero que
     * encuentre una. [cancelled] se consulta cada 1024 hashes (plazo agotado o corrutina cancelada).
     * @return la solución, o null si se canceló antes de encontrarla.
     */
    fun solve(nonce: String, difficulty: Int, threads: Int, cancelled: () -> Boolean): Long? {
        val mid = IV.copyOf()
        for (b in latin1("$nonce:")) absorb(mid, b.toInt() and 0xFF)
        val found = AtomicLong(-1)
        val workers = (0 until threads.coerceAtLeast(1)).map { k ->
            Thread({
                val r = IntArray(N)
                val digits = ByteArray(20)
                val step = threads.coerceAtLeast(1).toLong()
                var s = k.toLong()
                var n = 0
                while (found.get() < 0) {
                    if (zerosFrom(mid, digits, writeDecimal(s, digits), r) >= difficulty) {
                        found.compareAndSet(-1, s)
                        break
                    }
                    if ((++n and 1023) == 0 && cancelled()) break
                    s += step
                }
            }, "byse-pow-$k").apply { isDaemon = true; start() }
        }
        workers.forEach { it.join() }
        return found.get().takeIf { it >= 0 }
    }

    // ── La función ────────────────────────────────────────────────────────────────────────────

    private fun qr(t: IntArray) {
        t[0] += t[1]; t[3] = Integer.rotateLeft(t[3] xor t[0], 16)
        t[2] += t[3]; t[1] = Integer.rotateLeft(t[1] xor t[2], 12)
        t[0] += t[1]; t[3] = Integer.rotateLeft(t[3] xor t[0], 8)
        t[2] += t[3]; t[1] = Integer.rotateLeft(t[1] xor t[2], 7)
    }

    private fun absorb(e: IntArray, byte: Int) {
        e[0] += byte
        e[0] = Integer.rotateLeft(e[0], 7)
        qr(e)
    }

    /**
     * Todo lo que va después de absorber la entrada: rellenar el búfer, las dos pasadas de mezcla y
     * la salida. Con [wholeOutput] false para en cuanto una palabra no es cero —solo importan los
     * ceros iniciales—, que es exacto para cualquier dificultad y ahorra 7/8 del plegado final.
     */
    private fun finish(e: IntArray, r: IntArray, wholeOutput: Boolean): Pair<Int, IntArray> {
        repeat(8) { qr(e) }
        for (i in 0 until N) { qr(e); r[i] = e[0] xor e[2] }
        repeat(MIX_ROUNDS) {
            for (s in 0 until N) {
                val rs = r[s]
                val c = Integer.rotateLeft(rs + r[rs and MASK], 13) xor (r[(s + 1) and MASK] * LR)
                r[s] = c
                e[0] = e[0] xor c
                qr(e)
            }
        }
        val out = IntArray(8)
        var zeros = 0
        for (i in 0 until 8) {
            qr(e)
            var s = e[0]
            val base = i * (N / 8)
            for (c in 0 until N / 8) {
                val d = r[base + c]
                s = Integer.rotateLeft(s + d, 5) xor (d * HR)
            }
            out[i] = s xor e[2]
            if (!wholeOutput) {
                if (out[i] != 0) return (zeros + Integer.numberOfLeadingZeros(out[i])) to out
                zeros += 32
            }
        }
        return leadingZeroBits(out) to out
    }

    /** Ceros iniciales de hash("<nonce>:" + dígitos), partiendo del estado ya absorbido del prefijo. */
    private fun zerosFrom(mid: IntArray, digits: ByteArray, len: Int, r: IntArray): Int {
        val e = mid.copyOf()
        for (i in 0 until len) absorb(e, digits[i].toInt() and 0xFF)
        return finish(e, r, wholeOutput = false).first
    }

    /** [n] en decimal, como `String(n)` en JS; devuelve la longitud. */
    private fun writeDecimal(n: Long, buf: ByteArray): Int {
        if (n == 0L) { buf[0] = '0'.code.toByte(); return 1 }
        var len = 0
        var t = n
        while (t > 0) { len++; t /= 10 }
        var v = n
        for (i in len - 1 downTo 0) { buf[i] = ('0'.code + (v % 10).toInt()).toByte(); v /= 10 }
        return len
    }
}
