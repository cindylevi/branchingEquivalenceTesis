package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Refinable partition de estados (Jansen, Groote, Keiren, Wijs 2019, §5.1
 * opt #4). Reemplaza la representación naive {@code List<Set<Long>>} por un
 * único array maestro de estados sobre el cual cada bloque es un slice
 * contiguo.
 *
 * <p>Adentro de cada bloque hay tres rangos disjuntos:
 * <pre>
 *  start            bottomEnd          rDestEnd            end
 *    │                 │                  │                  │
 *    ▼                 ▼                  ▼                  ▼
 *    [    bottoms    ][   non-bottom    ][   R-destined    ]
 * </pre>
 * con la invariante {@code start ≤ bottomEnd ≤ rDestEnd ≤ end}.
 *
 * <p>Las operaciones críticas para {@code BranchingEquivalence}:
 * <ul>
 *   <li>{@link #blockOf(long)} — O(1)</li>
 *   <li>{@link #addToR(long)} / {@link #clearR(Block)} — O(1)</li>
 *   <li>{@link #splitOffR(Block)} — O(|b|) (sin copia de estados; sólo
 *       reasignación de {@code blockOfElement} en el sufijo)</li>
 *   <li>{@link #markAsBottom(long)} / {@link #unmarkAsBottom(long)} — O(1)</li>
 * </ul>
 *
 * <p>El detalle del diseño está en
 * {@code versiones/RefinablePartition design notes.md}.
 */
public final class RefinablePartition {

    public static final class Block {
        int start;
        int end;
        int bottomEnd;
        int rDestEnd;
        public final int id;
        boolean alive;

        Block(int start, int end, int id) {
            this.start = start;
            this.end = end;
            this.bottomEnd = start;
            this.rDestEnd = end;
            this.id = id;
            this.alive = true;
        }

        public int size()          { return end - start; }
        public int bottomCount()   { return bottomEnd - start; }
        public int rDestCount()    { return end - rDestEnd; }
        public boolean isAlive()   { return alive; }
        public int start()         { return start; }
        public int end()           { return end; }
    }

    private final Map<Long, Integer> denseIdOf;
    private final long[] elements;
    private final int[] position;
    private final Block[] blockOfElement;
    private final List<Block> liveBlocks;
    private int nextBlockId;

    public RefinablePartition(Collection<Long> universe) {
        int n = universe.size();
        this.elements = new long[n];
        this.position = new int[n];
        this.blockOfElement = new Block[n];
        this.denseIdOf = new HashMap<>(n * 2);

        int i = 0;
        for (Long s : universe) {
            denseIdOf.put(s, i);
            elements[i] = s;
            position[i] = i;
            i++;
        }

        Block all = new Block(0, n, 0);
        this.nextBlockId = 1;
        for (int k = 0; k < n; k++) blockOfElement[k] = all;
        this.liveBlocks = new ArrayList<>();
        if (n > 0) liveBlocks.add(all);
    }

    /**
     * Reordena {@code elements} para que cada bloque de {@code initialBlocks}
     * sea un slice contiguo, descartando el bloque universal. Los estados que
     * no aparezcan en ningún bloque inicial se ignoran (no quedan en ninguna
     * partición); en el uso típico, la unión de los initialBlocks coincide
     * con el universo entero.
     *
     * <p>Los bloques resultantes preservan el orden de {@code initialBlocks}.
     */
    public void seedFromInitialBlocks(List<Set<Long>> initialBlocks) {
        if (liveBlocks.size() != 1) {
            throw new IllegalStateException(
                    "seedFromInitialBlocks must be called once on a fresh partition");
        }
        Block universal = liveBlocks.get(0);
        universal.alive = false;
        liveBlocks.clear();

        int cursor = 0;
        List<Block> newBlocks = new ArrayList<>(initialBlocks.size());
        for (Set<Long> initial : initialBlocks) {
            int blockStart = cursor;
            for (Long s : initial) {
                Integer denseId = denseIdOf.get(s);
                if (denseId == null) continue; // estado no estaba en el universo
                int currentPos = position[denseId];
                if (currentPos != cursor) {
                    swap(currentPos, cursor);
                }
                cursor++;
            }
            int blockEnd = cursor;
            if (blockEnd > blockStart) {
                Block b = new Block(blockStart, blockEnd, nextBlockId++);
                for (int k = blockStart; k < blockEnd; k++) blockOfElement[k] = b;
                newBlocks.add(b);
            }
        }
        liveBlocks.addAll(newBlocks);
    }

    public int size() {
        return elements.length;
    }

    /** Devuelve el bloque vivo que contiene a {@code s}. O(1). */
    public Block blockOf(long s) {
        Integer denseId = denseIdOf.get(s);
        if (denseId == null) return null;
        return blockOfElement[position[denseId]];
    }

    public List<Block> liveBlocks() {
        return Collections.unmodifiableList(liveBlocks);
    }

    /** Itera los estados del bloque (rango [start, end)). */
    public Iterable<Long> states(Block b) {
        return rangeView(b.start, b.end);
    }

    /** Itera los estados marcados como bottom (rango [start, bottomEnd)). */
    public Iterable<Long> bottoms(Block b) {
        return rangeView(b.start, b.bottomEnd);
    }

    /** Itera los estados marcados como R-destined (rango [rDestEnd, end)). */
    public Iterable<Long> rDestStates(Block b) {
        return rangeView(b.rDestEnd, b.end);
    }

    /**
     * Mueve {@code s} al sufijo R-destined del bloque al que pertenece. Si
     * {@code s} ya estaba en R, no hace nada (idempotente). Si {@code s}
     * estaba en el prefijo bottom, también lo saca de ahí (deja de ser bottom
     * del bloque viejo).
     */
    public void addToR(long s) {
        int pos = position[denseIdOf.get(s)];
        Block b = blockOfElement[pos];
        if (pos >= b.rDestEnd) return;
        if (pos < b.bottomEnd) {
            swap(pos, b.bottomEnd - 1);
            b.bottomEnd--;
            pos = b.bottomEnd;
        }
        swap(pos, b.rDestEnd - 1);
        b.rDestEnd--;
    }

    /** Olvida que cualquier estado estaba en R; rDestEnd vuelve a end. O(1). */
    public void clearR(Block b) {
        b.rDestEnd = b.end;
    }

    /**
     * Parte {@code b} en dos bloques: uno con los R-destined, otro con el
     * resto. El bloque viejo queda muerto. Devuelve (rBlock, uBlock); cualquiera
     * de los dos puede ser {@code null} si quedó vacío.
     */
    public Pair<Block, Block> splitOffR(Block b) {
        if (!b.alive) {
            throw new IllegalStateException("cannot split a dead block");
        }
        if (b.rDestEnd == b.end) {
            // R vacío: nadie pasó a R. El bloque queda como estaba.
            return new Pair<>(null, b);
        }
        if (b.rDestEnd == b.start) {
            // U vacío: el bloque entero es R. Lo dejamos vivo, sólo limpiamos R.
            b.rDestEnd = b.end;
            return new Pair<>(b, null);
        }
        b.alive = false;
        liveBlocks.remove(b);

        Block uBlock = new Block(b.start, b.rDestEnd, nextBlockId++);
        Block rBlock = new Block(b.rDestEnd, b.end, nextBlockId++);
        for (int i = uBlock.start; i < uBlock.end; i++) blockOfElement[i] = uBlock;
        for (int i = rBlock.start; i < rBlock.end; i++) blockOfElement[i] = rBlock;
        liveBlocks.add(uBlock);
        liveBlocks.add(rBlock);
        return new Pair<>(rBlock, uBlock);
    }

    /**
     * Mueve {@code s} al prefijo bottom del bloque. Si ya era bottom, no hace
     * nada. Falla si {@code s} estaba en R: los bottoms se computan después
     * de splits, no durante.
     */
    public void markAsBottom(long s) {
        int pos = position[denseIdOf.get(s)];
        Block b = blockOfElement[pos];
        if (pos < b.bottomEnd) return;
        if (pos >= b.rDestEnd) {
            throw new IllegalStateException(
                    "cannot mark an R-destined state as bottom; clearR or splitOffR first");
        }
        swap(pos, b.bottomEnd);
        b.bottomEnd++;
    }

    /** Saca {@code s} del prefijo bottom (lo manda al medio). */
    public void unmarkAsBottom(long s) {
        int pos = position[denseIdOf.get(s)];
        Block b = blockOfElement[pos];
        if (pos >= b.bottomEnd) return;
        swap(pos, b.bottomEnd - 1);
        b.bottomEnd--;
    }

    /** Reset rápido: bottomEnd := start. Los estados quedan en el medio. */
    public void resetBottoms(Block b) {
        b.bottomEnd = b.start;
    }

    /**
     * Vista {@code List<Set<Long>>} de los bloques vivos. Pensada para los
     * consumidores externos (como {@code buildMinimisedMTS}) que esperan la
     * representación legacy.
     */
    public List<Set<Long>> asLegacyView() {
        List<Set<Long>> out = new ArrayList<>(liveBlocks.size());
        for (Block b : liveBlocks) {
            Set<Long> view = new HashSet<>(b.size());
            for (Long s : states(b)) view.add(s);
            out.add(view);
        }
        return out;
    }

    private void swap(int i, int j) {
        if (i == j) return;
        long si = elements[i];
        long sj = elements[j];
        elements[i] = sj;
        elements[j] = si;
        position[denseIdOf.get(si)] = j;
        position[denseIdOf.get(sj)] = i;
    }

    private Iterable<Long> rangeView(final int from, final int to) {
        return () -> new Iterator<Long>() {
            int cursor = from;
            @Override public boolean hasNext() { return cursor < to; }
            @Override public Long next() {
                if (cursor >= to) throw new NoSuchElementException();
                return elements[cursor++];
            }
        };
    }
}
