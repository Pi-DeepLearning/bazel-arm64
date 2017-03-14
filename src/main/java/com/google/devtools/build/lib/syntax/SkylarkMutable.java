// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.syntax;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.Mutability.Freezable;
import com.google.devtools.build.lib.syntax.Mutability.MutabilityException;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Base class for data structures that are only mutable with a proper Mutability.
 */
abstract class SkylarkMutable implements Freezable, SkylarkValue {

  protected SkylarkMutable() {}

  /**
   * Check whether this object is mutable in the current evaluation Environment.
   * @throws EvalException if the object was not mutable.
   */
  protected void checkMutable(Location loc, Environment env) throws EvalException {
    try {
      Mutability.checkMutable(this, env);
    } catch (MutabilityException ex) {
      throw new EvalException(loc, ex);
    }
  }

  @Override
  public boolean isImmutable() {
    return !mutability().isMutable();
  }

  @Override
  public String toString() {
    return Printer.repr(this);
  }

  /**
   * Add a new lock at {@code loc}. No effect if frozen.
   */
  public void lock(Location loc) {
    mutability().lock(this, loc);
  }

  /**
   * Remove the lock at {@code loc}; such a lock must already exist. No effect if frozen.
   */
  public void unlock(Location loc) {
    mutability().unlock(this, loc);
  }

  abstract static class MutableCollection<E> extends SkylarkMutable implements Collection<E> {

    protected MutableCollection() {}

    /**
     * The underlying contents is a (usually) mutable data structure.
     * Read access is forwarded to these contents.
     * This object must not be modified outside an {@link Environment}
     * with a correct matching {@link Mutability},
     * which should be checked beforehand using {@link #checkMutable}.
     * it need not be an instance of {@link com.google.common.collect.ImmutableCollection}.
     */
    protected abstract Collection<E> getContentsUnsafe();

    @Override
    public Iterator<E> iterator() {
      return getContentsUnsafe().iterator();
    };

    @Override
    public int size() {
      return getContentsUnsafe().size();
    }

    @Override
    public final Object[] toArray() {
      return getContentsUnsafe().toArray();
    }

    @Override
    public final <Object> Object[] toArray(Object[] other) {
      return getContentsUnsafe().toArray(other);
    }

    @Override
    public boolean isEmpty() {
      return getContentsUnsafe().isEmpty();
    }

    @Override
    public final boolean contains(@Nullable Object object) {
      return getContentsUnsafe().contains(object);
    }

    @Override
    public final boolean containsAll(Collection<?> collection) {
      return getContentsUnsafe().containsAll(collection);
    }

    // Disable all mutation interfaces without a mutation context.

    @Deprecated
    @Override
    public final boolean add(E element) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final boolean addAll(Collection<? extends E> collection) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final boolean remove(Object object) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final boolean removeAll(Collection<?> collection) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final boolean retainAll(Collection<?> collection) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final void clear() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
      return getContentsUnsafe().equals(o);
    }

    @Override
    public int hashCode() {
      return getContentsUnsafe().hashCode();
    }
  }

  abstract static class MutableMap<K, V> extends SkylarkMutable implements Map<K, V> {

    MutableMap() {}

    /**
     * The underlying contents is a (usually) mutable data structure.
     * Read access is forwarded to these contents.
     * This object must not be modified outside an {@link Environment}
     * with a correct matching {@link Mutability},
     * which should be checked beforehand using {@link #checkMutable}.
     */
    protected abstract Map<K, V> getContentsUnsafe();

    // A SkylarkDict forwards all read-only access to the contents.
    @Override
    public final V get(Object key) {
      return getContentsUnsafe().get(key);
    }

    @Override
    public boolean containsKey(Object key) {
      return getContentsUnsafe().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      return getContentsUnsafe().containsValue(value);
    }

    @Override
      public Set<Map.Entry<K, V>> entrySet() {
      return getContentsUnsafe().entrySet();
    }

    @Override
    public Set<K> keySet() {
      return getContentsUnsafe().keySet();
    }

    @Override
    public Collection<V> values() {
      return getContentsUnsafe().values();
    }

    @Override
    public int size() {
      return getContentsUnsafe().size();
    }

    @Override
    public boolean isEmpty() {
      return getContentsUnsafe().isEmpty();
    }

    // Disable all mutation interfaces without a mutation context.

    @Deprecated
    @Override
    public final V put(K key, V value) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final void putAll(Map<? extends K, ? extends V> map) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final V remove(Object key) {
      throw new UnsupportedOperationException();
    }

    @Deprecated
    @Override
    public final void clear() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
      return getContentsUnsafe().equals(o);
    }

    @Override
    public int hashCode() {
      return getContentsUnsafe().hashCode();
    }
  }
}
