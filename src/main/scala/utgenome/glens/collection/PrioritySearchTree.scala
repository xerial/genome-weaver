package utgenome.glens.collection

/*
 * Copyright 2012 Taro L. Saito
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//--------------------------------------
//
// PrioritySearchTree.scala
// Since: 2012/07/30 12:01 PM
//
//--------------------------------------

import RedBlackTree._
import collection.mutable
import xerial.core.log.Logging


object GenPrioritySearchTree {

  /**
   * element holder
   * @tparam A
   */
  abstract class Holder[A] extends Iterable[A] {
    def elem: A
    def +(e: A): Holder[A]
    def iterator: Iterator[A]
  }

  private[collection] case class Single[A](elem: A) extends Holder[A] {
    def +(e: A) = Multiple(Vector(elem, e))
    override def toString = "[%s]".format(elem)
    def iterator = Iterator.single(elem)
  }

  /**
   * TODO
   * @param elems
   * @tparam A
   */
  private[collection] case class Multiple[A](elems: Vector[A]) extends Holder[A] {
    require(elems.length > 1, "elems must have more than one element")

    def elem: A = elems(0)
    def +(e: A) = Multiple(elems :+ e)
    override def toString = "[%s]".format(elems.mkString(", "))
    def iterator = elems.iterator
  }


}

object PrioritySearchTree {


  def empty[A](implicit iv: IntervalType[A, Int]) = new PrioritySearchTree[A](null, 0)

  def newBuilder[A](implicit iv: IntervalType[A, Int]): mutable.Builder[A, PrioritySearchTree[A]] = {
    new mutable.Builder[A, PrioritySearchTree[A]] {
      private var tree = PrioritySearchTree.empty[A]
      def +=(elem: A) = {
        tree += elem
        this
      }
      def clear() {  tree = PrioritySearchTree.empty[A]  }
      def result() = tree
    }
  }

  def apply[A](elems: A*)(implicit iv: IntervalType[A, Int]): PrioritySearchTree[A] = {
    val b = newBuilder[A]
    elems foreach { b += _ }
    b.result
  }

}


import GenPrioritySearchTree._


/**
 * Persistent balanced priority search tree implementation. x-values (interval's start points) are maintained in binary search tree, and the y-values (interval's end points) of the node in the path from the root to leaves
 * are sorted in descending order. This property is good for answering 3-sided queries [x1, x2] x [y1, infinity).
 *
 * This priority search tree allows insertion of the same intervals.
 *
 *
 * @param tree
 * @param size
 * @param iv
 * @tparam A
 */
class PrioritySearchTree[A](tree: Tree[A, Holder[A]], override val size: Int)
                           (implicit iv: IntervalType[A, Int])
  extends GenPrioritySearchTree[A, Int](tree, size)(iv, Ordering.Int) {

  override def +(k: A) = new PrioritySearchTree(root.update(k, null), size + 1)
}

/**
 * Priority search tree for [[utgenome.glens.collection.LInterval]]
 * @param tree
 * @param size
 * @param iv
 * @tparam A
 */
class LPrioritySearchTree[A](tree: Tree[A, Holder[A]], override val size: Int)
                            (implicit iv: IntervalType[A, Long])
  extends GenPrioritySearchTree[A, Long](tree, size)(iv, Ordering.Long) {

  override def +(k: A) = new LPrioritySearchTree(root.update(k, null), size + 1)
}


/**
 * A base class of the persistent priority search tree.
 *
 * @param tree
 * @param size
 * @param iv
 * @tparam A
 */
class GenPrioritySearchTree[A, V](tree: Tree[A, Holder[A]], override val size: Int)(implicit iv: IntervalType[A, V], ord:Ordering[V])
  extends RedBlackTree[A, Holder[A]] with Iterable[A] with Logging {

  protected def root: Tree[A, Holder[A]] = if (tree == null) Empty else tree
  protected def isSmaller(a: A, b: A): Boolean = iv.xIsSmaller(a, b)
  protected def updateTree(t: Tree[A, Holder[A]], key: A, value: Holder[A]): Tree[A, Holder[A]] = mkTree(t.isBlack, iv.yUpperBound(t.key, key), t.value + key, t.left, t.right)

  override def toString = tree.toString

  /**
   * Create a new key so that it becomes the y-upper bound of the children
   * @param c
   * @param l
   * @param r
   * @return
   */
  override protected def newKey(c: A, l: Option[A], r: Option[A]): A = {
    def m(k1: A, k2: Option[A]): A = k2.map(iv.yUpperBound(k1, _)).getOrElse(k1)
    val k = m(m(c, l), r)
    if(l.isDefined)
      require(iv.yIsSmallerThanOrEq(l.get, k), "[illegal upperbound] new key:%s, c:%s, left:%s, right:%s".format(k, c, l, r))
    if(r.isDefined)
      require(iv.yIsSmallerThanOrEq(r.get, k), "[illegal upperbound] new key:%s, c:%s, left:%s, right:%s".format(k, c, l, r))
    //trace("upper bound of (c:%s, l:%s, r:%s) = %s", iv.end(c), l map (iv.end(_)), r map (iv.end(_)), iv.end(k))
    k
  }

  override protected def newValue(key: A, value: Holder[A]): Holder[A] = Single(key)

  /**
   * Return a new tree appending a new element k to the tree.
   * @param k
   * @return
   */
  def +(k: A) = new GenPrioritySearchTree(root.update(k, null), size + 1)

  /**
   * @return maximum height of the tree
   */
  def height = {
    def height(t: Tree[A, Holder[A]], h: Int): Int = {
      if (t.isEmpty)
        h
      else
        math.max(height(t.left, h + 1), height(t.right, h + 1))
    }

    height(root, 0)
  }


  def iterator = root.iterator.flatMap(_._2)

  def get[A1 <: A](k: A): Option[A] = {
    root.lookup(k) match {
      case Empty => None
      case t => t.value.find(iv.==(_, k))
    }
  }

  /**
   * Report the intervals in the tree intersecting with the given range.
   * The result intervals are sorted by their start values in ascending order
   * @param range
   * @return
   */
  def intersectWith[R](range: R)(implicit iv2:IntervalType[R, V]): Iterator[A] = {
    def find(t: Tree[A, Holder[A]]): Iterator[A] = {
      trace("find range:%s, at key node:%s (left:%s, right:%s)", range, t.key, t.left.key, t.right.key)
      if (t.isEmpty || iv2.compareXY(range, t.key) > 0) {
        // This tree contains no answer since yUpperBound (t.key.x) < range.x
        Iterator.empty
      }
      else {
        def elementInThisNode = t.value.filter(iv.intersect(_, range))
        def right = if (iv.compareXY(t.key, range) <= 0) t.right.map(find) else Iterator.empty
        t.left.map(find) ++ elementInThisNode ++ right
      }
    }

    find(root)
  }

  override def first = {
    def findFirst(t: Tree[A, Holder[A]]): A = {
      if (t.isEmpty)
        null.asInstanceOf[A]
      else {
        val l = findFirst(t.left)
        if (l != null)
          l
        else
          t.key
      }
    }

    findFirst(root)
  }

  override def last = {
    def findLast(t: Tree[A, Holder[A]]): A = {
      if (t.isEmpty)
        null.asInstanceOf[A]
      else {
        val r = findLast(t.right)
        if (r != null)
          r
        else
          t.key
      }
    }

    findLast(root)

  }

  def range(from: Option[V], until: Option[V]): Iterator[A] = {
    def takeValue(t: Tree[A, Holder[A]]): Iterator[A] = {
      if (t.isEmpty)
        Iterator.empty
      else
        t.left.map(takeValue) ++ t.value.iterator ++ t.right.map(takeValue)
    }

    def find(t: Tree[A, Holder[A]]): Iterator[A] = {
      if (t.isEmpty)
        Iterator.empty
      else {
        (from, until) match {
          case (None, None) => t.map(takeValue)
          case (Some(s), _) if ord.compare(iv.start(t.key), s) < 0 => find(t.right)
          case (_, Some(e)) if ord.compare(e, iv.start(t.key)) < 0 => find(t.left)
          case _ => {
            find(t.left) ++ t.value.iterator ++ find(t.right)
          }
        }
      }
    }

    find(root)
  }
}


