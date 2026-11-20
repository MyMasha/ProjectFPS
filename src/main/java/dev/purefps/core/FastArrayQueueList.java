package dev.purefps.core;

import java.util.Arrays;
import java.util.LinkedList;

/**
 * File FIFO circulaire adossée à un tableau Object[] qui étend LinkedList<E> afin de satisfaire
 * la signature bytecode de Lists.newLinkedList() dans RenderGlobal.setupTerrain sans allouer
 * un seul LinkedList$Node pendant le parcours BFS de visibilité des chunks.
 */
public final class FastArrayQueueList<E> extends LinkedList<E> {

    private static final long serialVersionUID = 1L;

    private Object[] elements;
    private int head;
    private int tail;

    public FastArrayQueueList(int initialPowerOfTwoCapacity) {
        int cap = 16;
        while (cap < initialPowerOfTwoCapacity) {
            cap <<= 1;
        }
        this.elements = new Object[cap];
    }

    @Override
    public boolean add(E e) {
        Object[] buf = this.elements;
        int mask = buf.length - 1;
        buf[this.tail] = e;
        int nextTail = (this.tail + 1) & mask;
        if (nextTail == this.head) {
            grow();
        } else {
            this.tail = nextTail;
        }
        return true;
    }

    @Override
    public boolean offer(E e) {
        return add(e);
    }

    @Override
    @SuppressWarnings("unchecked")
    public E poll() {
        int h = this.head;
        if (h == this.tail) {
            return null;
        }
        Object[] buf = this.elements;
        E result = (E) buf[h];
        buf[h] = null;
        this.head = (h + 1) & (buf.length - 1);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E peek() {
        return (this.head == this.tail) ? null : (E) this.elements[this.head];
    }

    @Override
    public boolean isEmpty() {
        return this.head == this.tail;
    }

    @Override
    public int size() {
        return (this.tail - this.head) & (this.elements.length - 1);
    }

    @Override
    public void clear() {
        int h = this.head;
        int t = this.tail;
        if (h != t) {
            Object[] buf = this.elements;
            if (h < t) {
                Arrays.fill(buf, h, t, null);
            } else {
                Arrays.fill(buf, h, buf.length, null);
                Arrays.fill(buf, 0, t, null);
            }
            this.head = 0;
            this.tail = 0;
        }
    }

    private void grow() {
        Object[] oldBuf = this.elements;
        int oldCap = oldBuf.length;
        int newCap = oldCap << 1;
        Object[] newBuf = new Object[newCap];
        int h = this.head;
        int right = oldCap - h;
        System.arraycopy(oldBuf, h, newBuf, 0, right);
        System.arraycopy(oldBuf, 0, newBuf, right, h);
        this.elements = newBuf;
        this.head = 0;
        this.tail = oldCap;
    }
}
