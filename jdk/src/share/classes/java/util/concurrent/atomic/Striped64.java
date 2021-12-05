/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.atomic;
import java.util.function.LongBinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A package-local class holding common representation and mechanics
 * for classes supporting dynamic striping on 64bit values. The class
 * extends Number so that concrete subclasses must publicly do so.<br/>
 * Striped64类的设计核心思路是通过内部的分散计算来避免线程CAS竞争，以空间换时间。<br/>
 * base类似于AtomicInteger里面的value，在没有竞争的情况下，cells数组为null，
 * 这时只使用base进行累加；而一旦发生竞争，cells数组就上场了。<br/>
 * cells数组第一次初始化长度为2，以后每次扩容都变为原来的两倍，一直到cells数组的长度大于等于当前服务器CPU的核数。为什么呢？
 * 同一时刻能持有CPU时间片去并发操作同一个内存地址的最大线程数最多也就是CPU的核数。<br/>
 * 在存在线程争用的时候，每个线程被映射到cells[threadLocalRandomProbe & cells.length]位置的Cell元素，
 * 该线程对value所做的累加操作就执行在对应的Cell元素的值上，最终相当于将线程绑定到cells中的某个Cell对象上。
 */
@SuppressWarnings("serial")
abstract class Striped64 extends Number {
    /*
     * This class maintains a lazily-initialized table of atomically
     * updated variables, plus an extra "base" field. The table size
     * is a power of two. Indexing uses masked per-thread hash codes.
     * Nearly all declarations in this class are package-private,
     * accessed directly by subclasses.
     *
     * Table entries are of class Cell; a variant of AtomicLong padded
     * (via @sun.misc.Contended) to reduce cache contention. Padding
     * is overkill for most Atomics because they are usually
     * irregularly scattered in memory and thus don't interfere much
     * with each other. But Atomic objects residing in arrays will
     * tend to be placed adjacent to each other, and so will most
     * often share cache lines (with a huge negative performance
     * impact) without this precaution.
     *
     * In part because Cells are relatively large, we avoid creating
     * them until they are needed.  When there is no contention, all
     * updates are made to the base field.  Upon first contention (a
     * failed CAS on base update), the table is initialized to size 2.
     * The table size is doubled upon further contention until
     * reaching the nearest power of two greater than or equal to the
     * number of CPUS. Table slots remain empty (null) until they are
     * needed.
     *
     * A single spinlock ("cellsBusy") is used for initializing and
     * resizing the table, as well as populating slots with new Cells.
     * There is no need for a blocking lock; when the lock is not
     * available, threads try other slots (or the base).  During these
     * retries, there is increased contention and reduced locality,
     * which is still better than alternatives.
     *
     * The Thread probe fields maintained via ThreadLocalRandom serve
     * as per-thread hash codes. We let them remain uninitialized as
     * zero (if they come in this way) until they contend at slot
     * 0. They are then initialized to values that typically do not
     * often conflict with others.  Contention and/or table collisions
     * are indicated by failed CASes when performing an update
     * operation. Upon a collision, if the table size is less than
     * the capacity, it is doubled in size unless some other thread
     * holds the lock. If a hashed slot is empty, and lock is
     * available, a new Cell is created. Otherwise, if the slot
     * exists, a CAS is tried.  Retries proceed by "double hashing",
     * using a secondary hash (Marsaglia XorShift) to try to find a
     * free slot.
     *
     * The table size is capped because, when there are more threads
     * than CPUs, supposing that each thread were bound to a CPU,
     * there would exist a perfect hash function mapping threads to
     * slots that eliminates collisions. When we reach capacity, we
     * search for this mapping by randomly varying the hash codes of
     * colliding threads.  Because search is random, and collisions
     * only become known via CAS failures, convergence can be slow,
     * and because threads are typically not bound to CPUS forever,
     * may not occur at all. However, despite these limitations,
     * observed contention rates are typically low in these cases.
     *
     * It is possible for a Cell to become unused when threads that
     * once hashed to it terminate, as well as in the case where
     * doubling the table causes no thread to hash to it under
     * expanded mask.  We do not try to detect or remove such cells,
     * under the assumption that for long-running instances, observed
     * contention levels will recur, so the cells will eventually be
     * needed again; and for short-lived ones, it does not matter.
     */

    /**
     * Padded variant of AtomicLong supporting only raw accesses plus CAS.
     *
     * JVM intrinsics note: It would be possible to use a release-only
     * form of CAS here, if it were provided.
     */
    @sun.misc.Contended static final class Cell {
        volatile long value;
        Cell(long x) { value = x; }
        final boolean cas(long cmp, long val) {
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long valueOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                valueOffset = UNSAFE.objectFieldOffset
                    (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Number of CPUS, to place bound on table size */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    //基类Striped64内部3个重要成员：cells、base、cellsBusy，都是用volatile修饰的
    /**
     * Table of cells. When non-null, size is a power of 2.
     * 成员一：存放Cell的哈希表，大小为2的幂。用于分散线程CAS竞争.
     */
    transient volatile Cell[] cells;
    /**
     * Base value, used mainly when there is no contention, but also as
     * a fallback during table initialization races. Updated via CAS.
     * 成员二：基础值
     * 1.在没有竞争时会更新这个值
     * 2.在cells初始化时，cells不可用，也会尝试通过CAS操作值累加到base字段
     */
    transient volatile long base;
    /**
     * Spinlock (locked via CAS) used when resizing and/or creating Cells.
     * 成员三：自旋锁
     * 通过CAS操作加锁，为0表示cells数组没有处于创建、扩容阶段，为1表示正在
     * 创建或者扩展cells数组，不能进行新Cell元素的设置操作
     */
    transient volatile int cellsBusy;

    /**
     * Package-private default constructor
     */
    Striped64() {
    }

    /**
     * CASes the base field.<br\>
     * 使用CAS来更新base值
     */
    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    /**
     * CASes the cellsBusy field from 0 to 1 to acquire lock.<br\>
     * 将cellsBusy成员字段的值通过CAS操作改为1，表示目前的cells数组正在初始化或扩容中。<br\><br\>
     *
     * casCellsBusy()方法相当于锁的功能：当线程需要cells数组初始化或扩容时，需要调用casCellsBusy()方法，
     * 通过CAS方式将cellsBusy成员的值改为1，如果修改失败，就表示其他的线程正在进行数组初始化或扩容的操作。只有CAS操作成功，
     * cellsBusy成员的值被改为1，当前线程才能执行cells数组初始化或扩容的操作。在cells数组初始化或扩容的操作执行完成之后，
     * cellsBusy成员的值被改为0，这时不需要进行CAS修改，直接修改即可，因为不存在争用。<br\><br\>
     *
     * 当cellsBusy成员值为1时，表示cells数组正在被某个线程执行初始化或扩容操作，其他线程不能进行以下操作：<br\>
     * （1）对cells数组执行初始化。<br\>
     * （2）对cells数组执行扩容。<br\>
     * （3）如果cells数组中某个元素为null，就为该元素创建新的Cell对象。因为数组的结构正在修改，所以其他线程不能创建新的Cell对象。
     */
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    /**
     * Returns the probe value for the current thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.<br\>
     * longAccumulate()是Striped64中重要的方法，实现不同的线程更新各自Cell中的值，其实现逻辑类似于分段锁
     *
     * @param x the value
     * @param fn the update function, or null for add (this convention
     * avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call
     */
    final void longAccumulate(long x, LongBinaryOperator fn, boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        // 扩容意向，collide=true可以扩容，collide=false不可扩容
        boolean collide = false;                // True if last slot nonempty

        // 自旋，一直到操作成功
        for (;;) {
            // as表示cells引用,a 表示当前线程命中的Cell,n表示cells数组长度,v表示期望值
            Cell[] as; Cell a; int n; long v;
        /*
            自旋过程中，有三个大的if分支：
            ·CASE 1：表示cells已经初始化了，当前线程应该将数据写入对应的Cell中。
            ·CASE 2：cells还未初始化（as为null），本分支计划初始化cells，在此之前开始执行cellsBusy加锁，并且要求cellsBusy加锁成功。
            ·CASE 3：如果cellsBusy加锁失败，表示其他线程正在初始化cells，所以当前线程将值累加到base上。
        */

        /*
            CASE 1表示当前线程应该将数据写入对应的Cell中，又分为以下几种细分情况：
            ·CASE 1.1：表示当前线程对应的下标位置的Cell为null，需要创建新Cell。
            ·CASE 1.2：wasUncontended是add(…)方法传递进来的参数如果为false，就表示cells已经被初始化，并且线程对应位置的Cell元素也
            已经被初始化，但是当前线程对Cell元素的竞争修改失败。如果add方法中的条件语句CASE 5通过CAS尝试把cells[m%cells.length]位
            置的Cell对象的value值设置为v+x而失败了，就说明已经发生竞争，就将wasUncontended设置为false。如果wasUncontended为
            false，就需要重新计算prob的值，那么自旋操作进入下一轮循环。
            ·CASE 1.3：无论执行CASE 1分支的哪个子条件，都会在末尾执行h=advanceProb()语句rehash出一个新哈希值，然后命中新的Cell，
            如果新命中的Cell不为空，在此分支进行CAS更新，将Cell的值更新为a.value+x，如果更新成功，就跳出自旋操作；否则还得继续自旋。
            ·CASE 1.4：调整cells数组的扩容意向，然后进入下一轮循环。如果n≥NCPU条件成立，就表示cells数组大小已经大于等于CPU核数，
            扩容意向改为false，表示不扩容了；如果该条件不成立，就说明cells数组还可以扩容，尽管如此，如果cells != as为true，
            就表示其他线程已经扩容过了，也会将扩容意向改为false，表示当前循环不扩容了。当前线程调到CASE 1分支的末尾执行rehash操作重
            新计算prob的值，然后进入下一轮循环。
            ·CASE 1.5：如果!collide=true满足，就表示扩容意向不满足，设置扩容意向为true，但是不一定真的发生扩容，然后进入CASE 1分支
            末尾重新计算prob的值，接着进入下一轮循环。
            ·CASE 1.6：执行真正扩容的逻辑。其条件一cellsBusy==0为true表示当前cellsBusy的值为0（无锁状态），当前线程可以去竞争这
            把锁；其条件二casCellsBusy()表示当前线程获取锁成功，CAS操作cellsBusy改为0成功，可以执行扩容逻辑。
        */
            // CASE 1: 表示cells已经初始化了，当前线程应该将数据写入对应的Cell中.这个大的if分支有三个小分支
            if ((as = cells) != null && (n = as.length) > 0) {
                //CASE 1.1: true表示下标位置的Cell为null，需要创建new Cell
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell.cells数组没有处于创建、扩容阶段
                        Cell r = new Cell(x);   // Optimistically create
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                // CASE 1.2：当前线程竞争修改失败，wasUncontended为false
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                // CASE 1.3：当前线程rehash过哈希值，CAS更新Cell
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                             fn.applyAsLong(v, x))))
                    break;
                // CASE 1.4: 调整扩容意向，然后进入下一轮循环
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale.达到最大值，或as值已过期
                // CASE 1.5: 设置扩容意向为true，但是不一定真的发生扩容
                else if (!collide)
                    collide = true;
                // CASE 1.6：真正扩容的逻辑
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0; // 释放锁
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h); // 重置（rehash）当前线程的hash值
            }
            // CASE 2：cells还未初始化（as为null），并且cellsBusy加锁成功
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(x);
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            // CASE 3：当前线程cellsBusy加锁失败，表示其他线程正在初始化cells，所以当前线程将值累加到base，
            // 注意add(…)方法调用此方法时fn为null
            else if (casBase(v = base, ((fn == null) ? v + x :
                                        fn.applyAsLong(v, x))))
                break;                          // Fall back on using base.在base操作成功时跳出自旋
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                               ((fn == null) ?
                                Double.doubleToRawLongBits
                                (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                (fn.applyAsDouble
                                 (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (casBase(v = base,
                             ((fn == null) ?
                              Double.doubleToRawLongBits
                              (Double.longBitsToDouble(v) + x) :
                              Double.doubleToRawLongBits
                              (fn.applyAsDouble
                               (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long BASE;
    private static final long CELLSBUSY;
    private static final long PROBE;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            PROBE = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
