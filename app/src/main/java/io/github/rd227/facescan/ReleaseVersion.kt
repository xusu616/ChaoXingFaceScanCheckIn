package io.github.rd227.facescan

internal object ReleaseVersion {
    fun compare(left: String, right: String): Int? {
        val pattern = Regex("[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?")
        val a = pattern.matchEntire(left.trim()) ?: return null
        val b = pattern.matchEntire(right.trim()) ?: return null
        val ap = a.groupValues[1].split('.').map { it.toBigInteger() }
        val bp = b.groupValues[1].split('.').map { it.toBigInteger() }
        for (index in 0 until maxOf(ap.size, bp.size)) {
            val difference = (ap.getOrNull(index) ?: java.math.BigInteger.ZERO)
                .compareTo(bp.getOrNull(index) ?: java.math.BigInteger.ZERO)
            if (difference != 0) return difference
        }
        val preA = a.groupValues[2]; val preB = b.groupValues[2]
        if (preA == preB) return 0
        if (preA.isEmpty()) return 1
        if (preB.isEmpty()) return -1
        val aa = preA.split('.'); val bb = preB.split('.')
        for (index in 0 until minOf(aa.size, bb.size)) {
            val x = aa[index]; val y = bb[index]
            val xn = x.toBigIntegerOrNull(); val yn = y.toBigIntegerOrNull()
            val difference = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (difference != 0) return difference
        }
        return aa.size.compareTo(bb.size)
    }
}
