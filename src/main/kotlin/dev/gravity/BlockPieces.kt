package dev.gravity

import kotlin.math.floor

/**
 * The pieces a broken block falls into. Members a lattice step apart are
 * bonded, members further apart are not (the next distance in a hexagonal
 * packing is sqrt 3 diameters), so a crack one member wide cuts a block in two.
 */
internal object BlockPieces {
    /** Largest distance, in contact diameters, at which two members are bonded. */
    const val BOND_DIAMETERS = 1.2f
    /** Bonds kept per member: discs a diameter apart fit seven within the bond length. */
    const val BONDS = 8
    /** Members a search takes in turn before the next search goes on. */
    private const val TURN = 64

    /** The bonds of each centre (xs, ys): [BONDS] indices per centre, -1 for none. */
    fun bonds(xs: FloatArray, ys: FloatArray, diameter: Float): IntArray {
        val count = xs.size
        val bonds = IntArray(count * BONDS) { -1 }
        if (count == 0) return bonds
        val reach = BOND_DIAMETERS * diameter
        // Centres bucketed on a grid of the bond length, as linked lists.
        val minX = xs.min()
        val minY = ys.min()
        val width = floor((xs.max() - minX) / reach).toInt() + 1
        val height = floor((ys.max() - minY) / reach).toInt() + 1
        val cellX = IntArray(count) { floor((xs[it] - minX) / reach).toInt().coerceIn(0, width - 1) }
        val cellY = IntArray(count) { floor((ys[it] - minY) / reach).toInt().coerceIn(0, height - 1) }
        val heads = IntArray(width * height) { -1 }
        val next = IntArray(count)
        for (index in 0 until count) {
            val cell = cellX[index] + cellY[index] * width
            next[index] = heads[cell]
            heads[cell] = index
        }
        val found = IntArray(count)
        for (index in 0 until count) {
            for (cy in maxOf(0, cellY[index] - 1)..minOf(height - 1, cellY[index] + 1)) {
                for (cx in maxOf(0, cellX[index] - 1)..minOf(width - 1, cellX[index] + 1)) {
                    var other = heads[cx + cy * width]
                    while (other >= 0) {
                        if (other > index) {
                            val dx = xs[other] - xs[index]
                            val dy = ys[other] - ys[index]
                            if (dx * dx + dy * dy < reach * reach && found[index] < BONDS && found[other] < BONDS) {
                                bonds[index * BONDS + found[index]++] = other
                                bonds[other * BONDS + found[other]++] = index
                            }
                        }
                        other = next[other]
                    }
                }
            }
        }
        return bonds
    }

    /** Indices of the centres (xs, ys) per piece, largest piece first. */
    fun of(xs: FloatArray, ys: FloatArray, diameter: Float): List<IntArray> {
        val bonds = bonds(xs, ys, diameter)
        val pieces = ArrayList<IntArray>()
        val seen = BooleanArray(xs.size)
        for (start in xs.indices) {
            if (seen[start]) continue
            val piece = IntList()
            piece.add(start)
            seen[start] = true
            var head = 0
            while (head < piece.size) {
                val member = piece[head++]
                for (bond in 0 until BONDS) {
                    val other = bonds[member * BONDS + bond]
                    if (other >= 0 && !seen[other]) {
                        seen[other] = true
                        piece.add(other)
                    }
                }
            }
            pieces += piece.toArray()
        }
        return pieces.sortedByDescending { it.size }
    }

    fun of(offsets: List<PixelOffset>, diameter: Float): List<IntArray> =
        of(FloatArray(offsets.size) { offsets[it].x }, FloatArray(offsets.size) { offsets[it].y }, diameter)

    /**
     * The pieces that came away from a block that held together, now that
     * some of its members are gone ([alive] says which are left). Searches
     * spread from [seeds], the members next to the ones gone, in turn; two
     * that meet go on as one, and one that runs out of members before meeting
     * the others has gone round a piece of its own. The search left last is
     * the rest of the block, never walked through, so the cost is that of the
     * pieces that came away, however large the block.
     */
    fun separated(bonds: IntArray, alive: BooleanArray, seeds: IntArray): List<IntArray> {
        val searches = seeds.size
        if (searches <= 1) return emptyList()
        val label = IntArray(alive.size) { -1 }
        val parents = IntArray(searches) { it }
        fun root(search: Int): Int {
            var node = search
            while (parents[node] != node) {
                parents[node] = parents[parents[node]]
                node = parents[node]
            }
            return node
        }
        // Every search keeps the members it reached; it goes on from [heads].
        val reached = Array(searches) { IntList() }
        val heads = IntArray(searches)
        var open = 0
        for (search in 0 until searches) {
            val seed = seeds[search]
            if (label[seed] >= 0) {
                parents[search] = root(label[seed])
                continue
            }
            label[seed] = search
            reached[search].add(seed)
            open++
        }
        val done = BooleanArray(searches)
        val pieces = ArrayList<IntArray>()
        while (open > 1) {
            for (search in 0 until searches) {
                if (done[root(search)]) continue
                var taken = 0
                while (taken < TURN && heads[search] < reached[search].size) {
                    val member = reached[search][heads[search]++]
                    taken++
                    for (bond in 0 until BONDS) {
                        val other = bonds[member * BONDS + bond]
                        if (other < 0 || !alive[other]) continue
                        if (label[other] < 0) {
                            label[other] = search
                            reached[search].add(other)
                        } else {
                            val mine = root(search)
                            val theirs = root(label[other])
                            if (mine != theirs) {
                                parents[maxOf(mine, theirs)] = minOf(mine, theirs)
                                open--
                            }
                        }
                    }
                }
            }
            // Searches that ran out of members went round a piece.
            val going = BooleanArray(searches)
            for (search in 0 until searches) {
                if (heads[search] < reached[search].size) going[root(search)] = true
            }
            for (search in 0 until searches) {
                if (root(search) != search || done[search] || going[search]) continue
                done[search] = true
                open--
                val piece = IntList()
                for (part in 0 until searches) {
                    if (root(part) == search) for (index in 0 until reached[part].size) piece.add(reached[part][index])
                }
                pieces += piece.toArray()
            }
        }
        // Everything came apart at once: the largest piece is what stays.
        if (open == 0 && pieces.isNotEmpty()) pieces.remove(pieces.maxBy { it.size })
        return pieces
    }

    /** A growable list of ints. */
    private class IntList {
        private var values = IntArray(16)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(2 * size)
            values[size++] = value
        }

        operator fun get(index: Int) = values[index]

        fun toArray() = values.copyOf(size)
    }
}
