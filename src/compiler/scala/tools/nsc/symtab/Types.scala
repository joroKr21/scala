/* NSC -- new Scala compiler
 * Copyright 2005-2006 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc.symtab

import scala.tools.nsc.util.Position
import nsc.util.HashSet
import scala.collection.mutable.ListBuffer
import Flags._

/* A standard type pattern match:
  case ErrorType =>
  case WildcardType =>
  case NoType =>
  case NoPrefix =>
  case ThisType(_) =>
  case SingleType(pre, sym) =>
  case ConstantType(value) =>
  case TypeRef(pre, sym, args) =>
  case TypeBounds(lo, hi) =>
  case RefinedType(parents, defs) =>
  case ClassInfoType(parents, defs, clazz) =>
  case MethodType(paramtypes, result) =>
  case PolyType(tparams, result) =>
  // the last three types are not used after phase `typer'.
  case OverloadedType(pre, tparams, alts) =>
  case AntiPolyType(pre: Type, targs) =>
  case TypeVar(_, _) =>
*/

trait Types requires SymbolTable {
  import definitions._

  //statistics
  var singletonClosureCount = 0
  var compoundClosureCount = 0
  var typerefClosureCount = 0
  var findMemberCount = 0
  var noMemberCount = 0
  var multMemberCount = 0
  var findMemberMillis = 0l
  var subtypeCount = 0
  var subtypeMillis = 0l

  private var explainSwitch = false
  private var checkMalformedSwitch = true

  val emptyTypeArray = new Array[Type](0)

  /** The base class for all types */
  abstract class Type {

    /** Types for which asSeenFrom always is the identity, no matter what prefix or owner */
    def isTrivial: boolean = false

    /** The symbol associated with the type */
    def symbol: Symbol = NoSymbol

    /** The base type underlying a singleton type,
     *  identity on all other types */
    def singleDeref: Type = this

    /** Widen from singleton type to its underlying non-singleton base type
     *  by applying one or more singleDeref steps,
     *  identity for all other types */
    def widen: Type = this

    /** The type of `this' of a class type or reference type
     */
    def typeOfThis = symbol.typeOfThis

    /** Map to a this type which is a subtype of this type.
     */
    def narrow: Type =
      if (phase.erasedTypes) this
      else refinedType(List(this), commonOwner(this), EmptyScope).narrow

    /** Map a constant type to its underlying base type,
     *  identity for all other types */
    def deconst: Type = this

    /** For a TypeBounds type, itself;
     *  for a reference denoting an abstract type, its bounds,
     *  for all other types, a TypeBounds type all of whose bounds are this type.
     *  error for all other types */
    def bounds: TypeBounds = TypeBounds(this, this);

    /** For a class or intersection type, its parents.
     *  For a TypeBounds type, the parents of its hi bound.
     *  inherited by typerefs, singleton types, and refinement types,
     *  The empty list for all other types */
    def parents: List[Type] = List()

    /** For a typeref or single-type, its prefix. NoType for all other types. */
    def prefix: Type = NoType;

    /** For a typeref, its arguments. The empty list for all other types */
    def typeArgs: List[Type] = List()

    /** For a method or poly type, its direct result type,
     *  the type itself for all other types */
    def resultType: Type = this

    /** For a curried method or poly type its non-method result type,
     *  the type itself for all other types */
    def finalResultType: Type = this

    /** For a method or poly type, the number of its value parameter sections,
     *  0 for all other types */
    def paramSectionCount: int = 0

    /** For a method or poly type, the types of its first value parameter section,
     *  the empty list for all other types */
    def paramTypes: List[Type] = List()

    /** For a poly type, its type parameters,
     *  the empty list for all other types */
    def typeParams: List[Symbol] = List()

    /** Is this type produced as a repair for an error? */
    def isError: boolean = symbol.isError

    /** Is this type produced as a repair for an error? */
    def isErroneous: boolean = {
      ErroneousTraverser.result = false
      ErroneousTraverser.traverse(this)
      ErroneousTraverser.result
    }

    /** Does this type denote a stable reference (i.e. singleton type)? */
    def isStable: boolean = false

    /** Does this type denote a reference type which can be null? */
    // def isNullable: boolean = false;

    /** Is this type guaranteed to be non-null? */
    // def isNonNull: boolean = false;

    /** For a classtype or refined type, its defined or declared members;
     *  inherited by subtypes and typerefs.
     *  The empty scope for all other types */
    def decls: Scope = EmptyScope;

    /** The defined or declared members with name `name' in this type;
     *  an OverloadedSymbol if several exist, NoSymbol if none exist.
     *  Alternatives of overloaded symbol appear in the order they are declared.
     */
    def decl(name: Name): Symbol = findDecl(name, 0)

    /** The non-private defined or declared members with name `name' in this type;
     *  an OverloadedSymbol if several exist, NoSymbol if none exist.
     *  Alternatives of overloaded symbol appear in the order they are declared.
     */
    def nonPrivateDecl(name: Name): Symbol = findDecl(name, PRIVATE)

    /** A list of all members of this type (defined or inherited)
     *  Members appear in linearization order of their owners.
     *  Members with the same owner appear in reverse order of their declarations.
     */
    def members: List[Symbol] = findMember(nme.ANYNAME, 0, 0).alternatives

    /** A list of all non-private members of this type (defined or inherited) */
    def nonPrivateMembers: List[Symbol] = findMember(nme.ANYNAME, PRIVATE | BRIDGE, 0).alternatives;

    /** A list of all implicit symbols of this type  (defined or inherited) */
    def implicitMembers: List[Symbol] = findMember(nme.ANYNAME, BRIDGE, IMPLICIT).alternatives;

    /** The member with given name,
     *  an OverloadedSymbol if several exist, NoSymbol if none exist */
    def member(name: Name): Symbol = findMember(name, BRIDGE, 0)

    /** The non-private member with given name,
     *  an OverloadedSymbol if several exist, NoSymbol if none exist */
    def nonPrivateMember(name: Name): Symbol = findMember(name, PRIVATE | BRIDGE, 0)

    /** The non-local member with given name,
     *  an OverloadedSymbol if several exist, NoSymbol if none exist */
    def nonLocalMember(name: Name): Symbol = findMember(name, LOCAL | BRIDGE, 0)

    /** The least type instance of given class which is a supertype
     *  of this type */
    def baseType(clazz: Symbol): Type = NoType

    /** This type as seen from prefix `
    pre' and class `clazz'. This means:
     *  Replace all thistypes of `clazz' or one of its subclasses by `pre'
     *  and instantiate all parameters by arguments of `pre'.
     *  Proceed analogously for thistypes referring to outer classes. */
    def asSeenFrom(pre: Type, clazz: Symbol): Type =
      if (!isTrivial && (!phase.erasedTypes || pre.symbol == ArrayClass)) {
        new AsSeenFromMap(pre, clazz) apply this
      } else this;

    /** The info of `sym', seen as a member of this type. */
    def memberInfo(sym: Symbol): Type =
      sym.info.asSeenFrom(this, sym.owner)

    /** The type of `sym', seen as a member of this type. */
    def memberType(sym: Symbol): Type = {
      sym.tpe match {
        case ov @ OverloadedType(pre, alts) =>
          OverloadedType(this, alts)
/*
          val pre1 = pre match {
            case ClassInfoType(_, _, clazz) => clazz.tpe
            case _ => pre
          }
          if (this =:= pre1) ov
          else if (this =:= pre1.narrow) OverloadedType(this, alts)
          else {
            Console.println("bad memberType of overloaded symbol: "+this+"/"+pre1+"/"+pre1.narrow)
            assert(false)
            ov
          }
*/
        case _ =>
          //System.out.println("" + this + ".memberType(" + sym +":" + sym.tpe +")");//DEBUG
          sym.tpe.asSeenFrom(this, sym.owner)
      }
    }

    /** Substitute types `to' for occurrences of references to symbols `from'
     *  in this type. */
    def subst(from: List[Symbol], to: List[Type]): Type =
      new SubstTypeMap(from, to) apply this;

    /** Substitute symbols `to' for occurrences of symbols `from' in this type. */
    def substSym(from: List[Symbol], to: List[Symbol]): Type =
      new SubstSymMap(from, to) apply this;

    /** Substitute all occurrences of ThisType(from) in this type by `to' */
    def substThis(from: Symbol, to: Type): Type =
      new SubstThisMap(from, to) apply this;

    def substSuper(from: Type, to: Type): Type =
      new SubstSuperMap(from, to) apply this;

    /** Does this type contain a reference to this symbol? */
    def contains(sym: Symbol): boolean =
      new ContainsTraverser(sym).traverse(this).result;

    /** Is this type a subtype of that type? */
    def <:<(that: Type): boolean = {
      if (util.Statistics.enabled) subtypeCount = subtypeCount + 1;
      val startTime = if (util.Statistics.enabled) System.currentTimeMillis() else 0l;
      val result =
        ((this eq that) ||
         (if (explainSwitch) explain("<", isSubType, this, that) else isSubType(this, that)));
      if (util.Statistics.enabled) subtypeMillis = subtypeMillis + System.currentTimeMillis() - startTime;
      result
    }

    /** Is this type equivalent to that type? */
    def =:=(that: Type): boolean = (
      (this eq that) ||
      (if (explainSwitch) explain("=", isSameType, this, that) else isSameType(this, that))
    );

    /** Does this type implement symbol `sym' with same or stronger type? */
    def specializes(sym: Symbol): boolean =
      if (explainSwitch) explain("specializes", specializesSym, this, sym)
      else specializesSym(this, sym);

    /** Is this type close enough to that type so that
     *  members with the two type would override each other?
     *  This means:
     *    - Either both types are polytypes with the same number of
     *      type parameters and their result types match after renaming
     *      corresponding type parameters
     *    - Or both types are method types with equivalent type parameter types
     *      and matching result types
     *    - Or both types are equivalent
     *    - Or phase.erasedTypes is false and both types are neither method nor
     *      poly types.
     */
    def matches(that: Type): boolean = matchesType(this, that);

    /** The shortest sorted upwards closed array of types that contains
     *  this type as first element.
     *
     *  A list or array of types ts is upwards closed if
     *
     *    for all t in ts:
     *      for all typerefs p.s[args] such that t &lt;: p.s[args]
     *      there exists a typeref p'.s[args'] in ts such that
     *      t &lt;: p'.s['args] &lt;: p.s[args],
     *      and
     *      for all singleton types p.s such that t &lt;: p.s
     *      there exists a singleton type p'.s in ts such that
     *      t &lt;: p'.s &lt;: p.s
     *
     *  Sorting is with respect to Symbol.isLess() on type symbols.
     */
    def closure: Array[Type] = Predef.Array(this)

    def baseClasses: List[Symbol] = List()

    /** The index of given class symbol in the closure of this type,
     *  or -1 if no base type with given class symbol exists */
    def closurePos(sym: Symbol): int = {
      val cl = closure
      var lo = 0
      var hi = cl.length - 1
      while (lo <= hi) {
        val mid = (lo + hi) / 2
        val clsym = cl(mid).symbol
        if (sym == clsym) return mid
        else if (sym isLess clsym) hi = mid - 1
        else if (clsym isLess sym) lo = mid + 1
        else throw new Error()
      }
      -1
    }

    /** If this is a polytype, a copy with cloned type parameters owned
     *  by `owner'. Identity for all other types. */
    def cloneInfo(owner: Symbol) = this

    protected def objectPrefix = "object "
    protected def packagePrefix = "package "

    def trimPrefix(str: String) =
      if (str.startsWith(objectPrefix)) str.substring(objectPrefix.length)
      else if (str.startsWith(packagePrefix)) str.substring(packagePrefix.length)
      else str

    /** The string representation of this type used as a prefix */
    def prefixString = trimPrefix(toString()) + "#";

    /** The string representation of this type, with singletypes explained */
    def toLongString = {
      val str = toString()
      if (str.endsWith(".type")) str + " (with underlying type " + widen + ")";
      else str
    }

    /** Is this type completed (i.e. not a lazy type)?
     */
    def isComplete: boolean = true;

    /** If this is a lazy type, assign a new type to `sym'. */
    def complete(sym: Symbol): unit = {
      if (sym == NoSymbol || sym.isPackageClass)
        sym.validTo = period(currentRunId, phaseId(sym.validTo))
      else {
        val this1 = adaptToNewRunMap(this)
        if (this1 eq this) sym.validTo = period(currentRunId, phaseId(sym.validTo))
        else {
          //System.out.println("new type of " + sym + "=" + this1 + ", used to be " + this);//DEBUG
          sym.setInfo(this1)
        }
      }
    }

    /** If this is a symbol loader type, load and assign a new type to `sym'. */
    def load(sym: Symbol): unit = {}

    private def findDecl(name: Name, excludedFlags: int): Symbol = {
      var alts: List[Symbol] = List()
      var sym: Symbol = NoSymbol
      var e: ScopeEntry = decls.lookupEntry(name)
      while (e != null) {
        if (!e.sym.hasFlag(excludedFlags)) {
          if (sym == NoSymbol) sym = e.sym
          else {
            if (alts.isEmpty) alts = List(sym);
            alts = e.sym :: alts
          }
        }
        e = decls.lookupNextEntry(e)
      }
      if (alts.isEmpty) sym
      else baseClasses.head.newOverloaded(this, alts)
    }

    //todo: use narrow only for modules? (correct? efficiency gain?)
    def findMember(name: Name, excludedFlags: int, requiredFlags: long): Symbol = {
      if (util.Statistics.enabled) findMemberCount = findMemberCount + 1;
      val startTime = if (util.Statistics.enabled) System.currentTimeMillis() else 0l;

      //System.out.println("find member " + name.decode + " in " + this + ":" + this.baseClasses);//DEBUG
      var members: Scope = null
      var member: Symbol = NoSymbol
      var excluded = excludedFlags | DEFERRED
      var self: Type = null
      var continue = true
      var savedCheckMalformedSwitch = checkMalformedSwitch
      checkMalformedSwitch = false
      while (continue) {
        continue = false;
        var bcs = baseClasses;
        while (!bcs.isEmpty) {
          val decls = bcs.head.info.decls;
          bcs = if (name == nme.CONSTRUCTOR) Nil else bcs.tail;
          var entry = if (name == nme.ANYNAME) decls.elems else decls lookupEntry name;
          while (entry != null) {
            val sym = entry.sym;
            if (sym.getFlag(requiredFlags) == requiredFlags) {
              val excl = sym.getFlag(excluded);
              if (excl == 0) {
                if (name.isTypeName) {
                  checkMalformedSwitch = savedCheckMalformedSwitch;
                  if (util.Statistics.enabled) findMemberMillis = findMemberMillis + System.currentTimeMillis() - startTime;
                  return sym
                } else if (member == NoSymbol) {
                  member = sym
                } else if (members == null) {
                  if (member.name != sym.name ||
                      member != sym &&
                      (member.owner == sym.owner || {
                         if (self == null) self = this.narrow;
                         !self.memberType(member).matches(self.memberType(sym))}))
                    members = new Scope(List(member, sym));
                } else {
                  var prevEntry = members lookupEntry sym.name;
                  while (prevEntry != null &&
                         !(prevEntry.sym == sym
                           ||
                           prevEntry.sym.owner != sym.owner &&
                           !prevEntry.sym.hasFlag(PRIVATE) &&
                           !sym.hasFlag(PRIVATE) && {
                             if (self == null) self = this.narrow;
                             (self.memberType(prevEntry.sym) matches self.memberType(sym))}))
                    prevEntry = members lookupNextEntry prevEntry;
                  if (prevEntry == null) {
                    members enter sym;
                  }
                }
              } else if (excl == DEFERRED) {
                continue = true;
              }
            }
            entry = if (name == nme.ANYNAME) entry.next else decls lookupNextEntry entry
          } // while (entry != null)
          // excluded = excluded | LOCAL
        } // while (!bcs.isEmpty)
        excluded = excludedFlags
      } // while (continue)
      checkMalformedSwitch = savedCheckMalformedSwitch;
      if (util.Statistics.enabled) findMemberMillis = findMemberMillis + System.currentTimeMillis() - startTime;
      if (members == null) {
        if (util.Statistics.enabled) if (member == NoSymbol) noMemberCount = noMemberCount + 1;
        member
      } else {
        if (util.Statistics.enabled) multMemberCount = multMemberCount + 1;
        //val pre = if (this.symbol.isClass) this.symbol.thisType else this;
        baseClasses.head.newOverloaded(this, members.toList)
      }
    }
  }

// Subclasses ------------------------------------------------------------

  trait UniqueType {
    private val hashcode = { val h = super.hashCode(); if (h < 0) -h else h }
    override def hashCode() = hashcode
  }

  /** A base class for types that defer some operations
   *  to their immediate supertype
   */
  abstract class SubType extends Type {
    def supertype: Type
    override def parents: List[Type] = supertype.parents
    override def decls: Scope = supertype.decls
    override def baseType(clazz: Symbol): Type = supertype.baseType(clazz)
    override def closure: Array[Type] = supertype.closure
    override def baseClasses: List[Symbol] = supertype.baseClasses
    // override def isNonNull = supertype.isNonNull
  }

  /** A base class for types that represent a single value
   *  (single-types and this-types)
   */
  abstract class SingletonType extends SubType {
    override def singleDeref: Type
    def supertype: Type = singleDeref
    override def isStable: boolean = true
    override def widen: Type = singleDeref.widen
    override def closure: Array[Type] = {
      if (util.Statistics.enabled) singletonClosureCount = singletonClosureCount + 1;
      addClosure(this, supertype.closure)
    }
    override def toString(): String = prefixString + "type"
  }

  /** An object representing an erroneous type */
  case object ErrorType extends Type {
    // todo see whether we can do without
    override def isError: boolean = true;
    override def decls: Scope = new ErrorScope(NoSymbol);
    override def findMember(name: Name, excludedFlags: int, requiredFlags: long): Symbol = {
      var sym = decls lookup name;
      if (sym == NoSymbol) {
        sym = NoSymbol.newErrorSymbol(name);
        decls enter sym
      }
      sym
    }
    override def baseType(clazz: Symbol): Type = this
    override def toString(): String = "<error>"
    override def narrow: Type = this
    // override def isNullable: boolean = true
  }

  /** An object representing an unknown type */
  case object WildcardType extends Type {
    override def toString(): String = "?"
    // override def isNullable: boolean = true
  }

  case class BoundedWildcardType(override val bounds: TypeBounds) extends Type {
    override def toString(): String = "?" + bounds
  }

  /** An object representing a non-existing type */
  case object NoType extends Type {
    override def isTrivial: boolean = true
    override def toString(): String = "<notype>"
    // override def isNullable: boolean = true
  }

  /** An object representing a non-existing prefix */
  case object NoPrefix extends Type {
    override def isTrivial: boolean = true
    override def isStable: boolean = true
    override def prefixString = ""
    override def toString(): String = "<noprefix>"
    // override def isNullable: boolean = true
  }

  /** A class for this-types of the form <sym>.this.type
   */
  abstract case class ThisType(sym: Symbol) extends SingletonType {
    //assert(sym.isClass && !sym.isModuleClass || sym.isRoot, sym)
    override def isTrivial: boolean = sym.isPackageClass
    // override def isNonNull = true
    override def symbol = sym
    override def singleDeref: Type = sym.typeOfThis
    override def prefixString =
      if (settings.debug.value) sym.nameString + ".this."
      else if (sym.isRoot || sym.isEmptyPackageClass) ""
      else if (sym.isAnonymousClass || sym.isRefinementClass) "this."
      else if (sym.isPackageClass) sym.fullNameString + "."
      else sym.nameString + ".this."
    override def toString(): String =
      if (sym.isRoot) "<root>"
      else if (sym.isEmptyPackageClass) "<empty>"
      else super.toString()
    override def narrow: Type = this;
  }

  /** A class for singleton types of the form &lt;prefix&gt;.&lt;sym.name&gt;.type.
   *  Cannot be created directly; one should always use
   *  `singleType' for creation.
   */
  abstract case class SingleType(pre: Type, sym: Symbol) extends SingletonType {
    override val isTrivial: boolean = pre.isTrivial
    // override def isNullable = supertype.isNullable
    // override def isNonNull = supertype.isNonNull
    private var singleDerefCache: Type = _
    private var singleDerefPeriod = NoPeriod
    override def singleDeref: Type = {
      val period = singleDerefPeriod
      if (period != currentPeriod) {
        singleDerefPeriod = currentPeriod
        if (!isValid(period)) {
          singleDerefCache = pre.memberType(sym).resultType;
        }
      }
      singleDerefCache
    }

    override def narrow: Type = {
      if (phase.erasedTypes) this
      else {
        val thissym = refinedType(List(this), sym.owner, EmptyScope).symbol
        if (sym.owner != NoSymbol) {
          //Console.println("narrowing module " + sym + thissym.owner);
          thissym.typeOfThis = this
        }
        thissym.thisType
      }
    }

    override def symbol = sym
    override def prefix: Type = pre
    override def prefixString: String =
      if (sym.isEmptyPackage && !settings.debug.value) ""
      else pre.prefixString + sym.nameString + ".";
  }

  abstract case class SuperType(thistpe: Type, supertp: Type) extends SingletonType {
    override val isTrivial: boolean = thistpe.isTrivial && supertp.isTrivial;
    // override def isNonNull = true;
    override def symbol = thistpe.symbol;
    override def singleDeref = supertp;
    override def prefix: Type = supertp.prefix;
    override def prefixString =
      if (thistpe.prefixString.endsWith("this."))
        thistpe.prefixString.substring(0, thistpe.prefixString.length() - 5) + "super."
      else thistpe.prefixString;
    override def narrow: Type = thistpe.narrow
  }

  /** A class for the bounds of abstract types and type parameters
   */
  abstract case class TypeBounds(lo: Type, hi: Type) extends SubType {
    override val isTrivial: boolean = lo.isTrivial && hi.isTrivial;
    def supertype: Type = hi;
    override def bounds: TypeBounds = this;
    def containsType(that: Type) = that <:< this || lo <:< that && that <:< hi;
    // override def isNullable: boolean = AllRefClass.tpe <:< lo;
    override def toString() = ">: " + lo + " <: " + hi;
  }

  /** A common base class for intersection types and class types
   */
  abstract class CompoundType extends Type {

    private var closureCache: Array[Type] = _
    private var closurePeriod = NoPeriod
    private var baseClassesCache: List[Symbol] = _
    private var baseClassesPeriod = NoPeriod

    override def closure: Array[Type] = {
      def computeClosure: Array[Type] =
        try {
          if (util.Statistics.enabled) compoundClosureCount = compoundClosureCount + 1
          //System.out.println("computing closure of " + symbol.tpe + " " + parents);//DEBUG
          val buf = new ListBuffer[Type]
          buf += symbol.tpe
          var clSize = 1
          val nparents = parents.length
          if (nparents != 0) {
            val pclosure = new Array[Array[Type]](nparents)
            val index = new Array[int](nparents)
            var i = 0
            for (val p <- parents) {
              pclosure(i) = p.closure
              index(i) = 0
              i = i + 1
            }
            def nextBaseType(i: int): Type = {
              val j = index(i);
              val pci = pclosure(i);
              if (j < pci.length) pci(j) else AnyClass.tpe
            }
            val limit = pclosure(0).length;
            while (index(0) != limit) {
              var minSym: Symbol = nextBaseType(0).symbol;
              i = 1;
              while (i < nparents) {
                if (nextBaseType(i).symbol isLess minSym) minSym = nextBaseType(i).symbol;
                i = i + 1
              }
              var minTypes: List[Type] = List();
              i = 0;
              while (i < nparents) {
                val tp = nextBaseType(i);
                if (tp.symbol == minSym) {
                  if (!(minTypes exists (tp =:=))) minTypes = tp :: minTypes;
                  index(i) = index(i) + 1
                }
                i = i + 1
              }
              buf += intersectionType(minTypes);
              clSize = clSize + 1;
            }
          }
          closureCache = new Array[Type](clSize);
          buf.copyToArray(closureCache, 0);
          //System.out.println("closureCache of " + symbol.tpe + " = " + List.fromArray(closureCache));//DEBUG
          var j = 0;
          while (j < clSize) {
            closureCache(j) match {
              case RefinedType(parents, decls) =>
                assert(decls.isEmpty);
                //Console.println("compute closure of "+this+" => glb("+parents+")")
                closureCache(j) = glbNoRefinement(parents)
              case _ =>
            }
            j = j + 1
          }
          //System.out.println("closure of " + symbol.tpe + " = " + List.fromArray(closureCache));//DEBUG
          closureCache
        } catch {
          case ex: MalformedClosure =>
            throw new MalformedType(
              "the type intersection " + this + " is malformed" +
              "\n --- because ---\n" + ex.getMessage())
        }
      val period = closurePeriod;
      if (period != currentPeriod) {
        closurePeriod = currentPeriod;
        if (!isValidForBaseClasses(period)) {
          closureCache = null;
          closureCache = computeClosure
        }
        //System.out.println("closure(" + symbol + ") = " + List.fromArray(closureCache));//DEBUG
      }
      if (closureCache == null)
        throw new TypeError("illegal cyclic reference involving " + symbol);
      closureCache;
    }

    override def baseClasses: List[Symbol] = {
      def computeBaseClasses: List[Symbol] =
        if (parents.isEmpty) List(symbol)
        else {
          //System.out.println("computing base classes of " + symbol + " at phase " + phase);//DEBUG
          // optimized, since this seems to be performance critical
          val superclazz = parents.head;
          var mixins = parents.tail;
          val sbcs = superclazz.baseClasses;
          var bcs = sbcs;
          def isNew(clazz: Symbol): boolean = (
            superclazz.closurePos(clazz) < 0 &&
            { var p = bcs;
              while ((p ne sbcs) && (p.head != clazz)) p = p.tail;
              p eq sbcs
            }
          );
          while (!mixins.isEmpty) {
            def addMixinBaseClasses(mbcs: List[Symbol]): List[Symbol] =
              if (mbcs.isEmpty) bcs
              else if (isNew(mbcs.head)) mbcs.head :: addMixinBaseClasses(mbcs.tail)
              else addMixinBaseClasses(mbcs.tail);
            bcs = addMixinBaseClasses(mixins.head.baseClasses);
            mixins = mixins.tail
          }
          symbol :: bcs
        }
      val period = baseClassesPeriod;
      if (period != currentPeriod) {
        baseClassesPeriod = currentPeriod;
        if (!isValidForBaseClasses(period)) {
          baseClassesCache = null;
          baseClassesCache = computeBaseClasses;
        }
      }
      if (baseClassesCache == null)
        throw new TypeError("illegal cyclic reference involving " + symbol);
      baseClassesCache
    }

    override def baseType(sym: Symbol): Type = {
      val index = closurePos(sym);
      if (index >= 0) closure(index) else NoType;
    }

    override def narrow: Type = symbol.thisType;

    // override def isNonNull: boolean = parents forall (.isNonNull);

    // override def isNullable: boolean =
    // parents forall (p => p.isNullable && !p.symbol.isAbstractType);

    override def toString(): String = (
      parents.mkString("", " with ", "") +
      (if (settings.debug.value || parents.isEmpty || decls.elems != null)
        decls.mkString("{", "; ", "}") else "")
    );
  }

  /** A class representing intersection types with refinements of the form
   *    <parents_0> with ... with <parents_n> { decls }
   *  Cannot be created directly;
   *  one should always use `refinedType' for creation.
   */
  abstract case class RefinedType(override val parents: List[Type],
                                  override val decls: Scope) extends CompoundType;

  /** A class representing a class info
   */
  case class ClassInfoType(
    override val parents: List[Type],
    override val decls: Scope,
    override val symbol: Symbol) extends CompoundType {

    // override def isNullable: boolean =
    // symbol == AnyClass ||
    // symbol != AllClass && (symbol isSubClass ObjectClass) && !(symbol isSubClass NonNullClass);

    // override def isNonNull: boolean = symbol == NonNullClass || super.isNonNull;
  }

  class PackageClassInfoType(decls: Scope, clazz: Symbol) extends ClassInfoType(List(), decls, clazz);

  /** A class representing a constant type */
  abstract case class ConstantType(value: Constant) extends SingletonType {
    assert(value.tpe.symbol != UnitClass)
    override def isTrivial: boolean = true
    override def symbol: Symbol = value.tpe.symbol
    override def singleDeref: Type = value.tpe
    override def deconst: Type = value.tpe
    override def toString(): String = value.tpe.toString() + "(" + value.stringValue + ")"
    // override def isNullable: boolean = value.value == null
    // override def isNonNull: boolean = value.value != null
  }

  /** A class for named types of the form <prefix>.<sym.name>[args]
   *  Cannot be created directly; one should always use `typeRef' for creation.
   */
  abstract case class TypeRef(pre: Type, sym: Symbol, args: List[Type]) extends Type {
    assert(!sym.isAbstractType || pre.isStable || pre.isError);
    assert(!pre.isInstanceOf[ClassInfoType], this);
    assert(!sym.isTypeParameterOrSkolem || pre == NoPrefix, this);

    private var parentsCache: List[Type] = _
    private var parentsPeriod = NoPeriod
    private var closureCache: Array[Type] = _
    private var closurePeriod = NoPeriod

    override val isTrivial: boolean =
      pre.isTrivial && !sym.isTypeParameter && args.forall(.isTrivial)

    def transform(tp: Type): Type =
      tp.asSeenFrom(pre, sym.owner).subst(sym.typeParams, args)

    def transform(cl: Array[Type]): Array[Type] = {
      val cl1 = new Array[Type](cl.length);
      var i = 0;
      while (i < cl.length) { cl1(i) = transform(cl(i)); i = i + 1 }
      cl1
    }

    override def symbol = sym;

    override def bounds: TypeBounds =
      if (sym.isAbstractType) transform(sym.info.bounds).asInstanceOf[TypeBounds]
      else super.bounds;

    override def parents: List[Type] = {
      val period = parentsPeriod;
      if (period != currentPeriod) {
        parentsPeriod = currentPeriod;
        if (!isValidForBaseClasses(period)) {
          parentsCache = sym.info.parents map transform
        }
      }
      parentsCache
    }

    override def typeOfThis = transform(sym.typeOfThis)

    override def narrow = if (sym.isModuleClass) transform(sym.thisType) else super.narrow

    override def prefix: Type = pre

    override def typeArgs: List[Type] = args

    override def typeParams: List[Symbol] =
      if (args.isEmpty) symbol.unsafeTypeParams else List()

    override def decls: Scope = {
      sym.info match {
        case TypeRef(_, sym1, _) =>
          assert(sym1 != symbol, this);
        case _ =>
      }
      sym.info.decls
    }

    override def baseType(clazz: Symbol): Type =
      if (sym == clazz) this
      else if (sym.isClass) transform(sym.info.baseType(clazz))
      else pre.memberInfo(sym).baseType(clazz);

    override def closure: Array[Type] = {
      val period = closurePeriod;
      if (period != currentPeriod) {
        closurePeriod = currentPeriod;
        if (!isValidForBaseClasses(period)) {
          if (util.Statistics.enabled) typerefClosureCount = typerefClosureCount + 1;
          closureCache =
            if (sym.isAbstractType) addClosure(this, transform(bounds.hi).closure)
            else transform(sym.info.closure);
        }
      }
      closureCache
    }

    override def baseClasses: List[Symbol] = sym.info.baseClasses;

    // override def isNullable: boolean = sym.info.isNullable

    override def toString(): String = {
      if (!settings.debug.value) {
        if (sym == RepeatedParamClass && !args.isEmpty)
          return args(0).toString() + "*";
        if (sym == ByNameParamClass && !args.isEmpty)
          return "=> " + args(0).toString();
        if (isFunctionType(this))
          return args.init.mkString("(", ", ", ")") + " => " + args.last;
      }
      val str = (pre.prefixString + sym.nameString +
                 (if (args.isEmpty) "" else args.mkString("[", ",", "]")))
      if (sym.isPackageClass)
        packagePrefix+str
      else if (sym.isModuleClass)
        objectPrefix+str
      else if (sym.isAnonymousClass && sym.isInitialized)
        sym.info.parents.mkString("", " with ", "{ ... }")
      else if (sym.isRefinementClass && sym.isInitialized)
        sym.info.toString()
      else str
    }

    override def prefixString =
      if (settings.debug.value) super.prefixString
      else if (sym.isRoot || sym.isEmptyPackageClass ||
               sym.isAnonymousClass || sym.isRefinementClass) ""
      else if (sym.isPackageClass) sym.fullNameString + "."
      else super.prefixString;
  }

  /** A class representing a method type with parameters.
   */
  case class MethodType(override val paramTypes: List[Type],
                        override val resultType: Type) extends Type {
    override val isTrivial: boolean =
      paramTypes.forall(.isTrivial) && resultType.isTrivial;

    //assert(paramTypes forall (pt => !pt.symbol.isImplClass));//DEBUG
    override def paramSectionCount: int = resultType.paramSectionCount + 1;

    override def finalResultType: Type = resultType.finalResultType;

    override def toString(): String = paramTypes.mkString("(", ",", ")") + resultType;
  }

  class ImplicitMethodType(pts: List[Type], rt: Type) extends MethodType(pts, rt) {
    override def toString(): String = paramTypes.mkString("(implicit ", ",", ")") + resultType;
  }

  class JavaMethodType(pts: List[Type], rt: Type) extends MethodType(pts, rt);

  /** A class representing a polymorphic type or, if tparams.length == 0,
   *  a parameterless method type.
   */
  case class PolyType(override val typeParams: List[Symbol], override val resultType: Type)
       extends Type {

    override def paramSectionCount: int = resultType.paramSectionCount
    override def paramTypes: List[Type] = resultType.paramTypes

    override def finalResultType: Type = resultType.finalResultType

    override def parents: List[Type] = resultType.parents
    override def decls: Scope = resultType.decls
    override def symbol: Symbol = resultType.symbol
    override def closure: Array[Type] = resultType.closure
    override def baseClasses: List[Symbol] = resultType.baseClasses
    override def baseType(clazz: Symbol): Type = resultType.baseType(clazz)
    override def narrow: Type = resultType.narrow

    // override def isNullable: boolean = resultType.isNullable;

    override def toString(): String =
      (if (typeParams.isEmpty) "=> "
       else (typeParams map (.defString)).mkString("[", ",", "]")) + resultType;

    override def cloneInfo(owner: Symbol) = {
      val tparams = cloneSymbols(typeParams, owner);
      PolyType(tparams, resultType.substSym(typeParams, tparams))
    }
  }

  /** A class containing the alternatives and type prefix of an overloaded symbol.
   *  Not used after phase `typer'.
   */
  case class OverloadedType(pre: Type, alternatives: List[Symbol]) extends Type {
    override def prefix: Type = pre
    override def toString() = (alternatives map pre.memberType).mkString("", " <and> ", "")
  }

  /** A class remembering a type instantiation for some a set of overloaded polymorphic symbols.
   *  Not used after phase `typer'.
   */
  case class AntiPolyType(pre: Type, targs: List[Type]) extends Type {
    override def toString() = pre.toString() + targs.mkString("(with type arguments ", ",", ")");
    override def memberType(sym: Symbol) = pre.memberType(sym) match {
      case PolyType(tparams, restp) => restp.subst(tparams, targs)
      case ErrorType => ErrorType
    }
  }

  /** A class representing a type variable
   *  Not used after phase `typer'.
   */
  case class TypeVar(origin: Type, constr: TypeConstraint) extends Type {
    override def symbol = origin.symbol
    override def toString(): String =
      if (constr.inst == null) "<null "+origin+">"
      else if (constr.inst eq NoType) "?" + origin
      else constr.inst.toString();
  }

  /** A class representing an as-yet unevaluated type.
   */
  abstract class LazyType extends Type {
    override def isComplete: boolean = false;
    override def complete(sym: Symbol): unit;
  }

  /** A class representing a lazy type with known type parameters
   */
  class LazyPolyType(override val typeParams: List[Symbol], restp: Type) extends LazyType {
    override def complete(sym: Symbol): unit = {
      restp.complete(sym)
    }
  }

// Creators ---------------------------------------------------------------

  /** Rebind symbol `sym' to an overriding member in type `pre' */
  private def rebind(pre: Type, sym: Symbol): Symbol = {
    val owner = sym.owner;
    if (owner.isClass && owner != pre.symbol && !sym.isFinal && !sym.isClass) {
      //Console.println("rebind "+pre+" "+sym)//DEBUG
      val rebind = pre.nonPrivateMember(sym.name).suchThat(sym => sym.isType || sym.isStable);
      if (rebind == NoSymbol) sym else rebind
    } else sym
  }

  /** Convert a `super' prefix to a this-type if `sym' is abstract or final */
  private def removeSuper(tp: Type, sym: Symbol): Type = tp match {
    case SuperType(thistp, _) =>
      if (sym.isFinal || sym.hasFlag(DEFERRED)) thistp
      else tp
    case _ =>
      tp
  }

  /** The canonical creator for this-types */
  def ThisType(sym: Symbol): Type =
    if (phase.erasedTypes) sym.tpe else unique(new ThisType(sym) with UniqueType);

  /** The canonical creator for single-types */
  def singleType(pre: Type, sym: Symbol): Type = {
    if (phase.erasedTypes)
      sym.tpe.resultType
    else if (checkMalformedSwitch && !pre.isStable && !pre.isError)
      throw new MalformedType(pre, sym.name.toString())
    else {
      var sym1 = rebind(pre, sym)
      val pre1 = removeSuper(pre, sym1)
      if (pre1 ne pre) sym1 = rebind(pre1, sym1)
      unique(new SingleType(pre1, sym1) with UniqueType)
    }
  }

  /** The canonical creator for super-types */
  def SuperType(thistp: Type, supertp: Type): Type =
    if (phase.erasedTypes) supertp
    else unique(new SuperType(thistp, supertp) with UniqueType);

  /** The canonical creator for type bounds */
  def TypeBounds(lo: Type, hi: Type): TypeBounds =
    unique(new TypeBounds(lo, hi) with UniqueType);

  /** the canonical creator for a refined type with a given scope */
  def refinedType(parents: List[Type], owner: Symbol, decls: Scope): Type = {
    if (phase.erasedTypes)
      if (parents.isEmpty) ObjectClass.tpe else parents.head
    else {
      val clazz = owner.newRefinementClass(Position.NOPOS);
      val result = new RefinedType(parents, decls) { override def symbol: Symbol = clazz }
      clazz.setInfo(result);
      result
    }
  }

  /** the canonical creator for a refined type with an initially empty scope */
  def refinedType(parents: List[Type], owner: Symbol): Type =
    refinedType(parents, owner, new Scope)

  /** the canonical creator for a constant type */
  def ConstantType(value: Constant): ConstantType =
    unique(new ConstantType(value) with UniqueType);

  /** The canonical creator for typerefs */
  def typeRef(pre: Type, sym: Symbol, args: List[Type]): Type = {
    var sym1 = if (sym.isAbstractType) rebind(pre, sym) else sym;
    if (checkMalformedSwitch && sym1.isAbstractType && !pre.isStable && !pre.isError)
      throw new MalformedType(pre, sym.nameString);
//    if (sym1.hasFlag(LOCKED))
//      throw new TypeError("illegal cyclic reference involving " + sym1);
    if (sym1.isAliasType && sym1.info.typeParams.length == args.length) {
      // note: we require that object is initialized,
      // that's why we use info.typeParams instead of typeParams.
      if (sym1.hasFlag(LOCKED))
        throw new TypeError("illegal cyclic reference involving " + sym1);
      sym1.setFlag(LOCKED);
      val result = sym1.info.resultType.asSeenFrom(pre, sym1.owner).subst(sym1.typeParams, args);
      sym1.resetFlag(LOCKED);
      result
    } else {
      val pre1 = removeSuper(pre, sym1)
      if ((pre1 ne pre) && sym1.isAbstractType) sym1 = rebind(pre1, sym1)
      rawTypeRef(pre1, sym1, args)
    }
  }

  /** create a type-ref as found, without checks or rebinds */
  def rawTypeRef(pre: Type, sym: Symbol, args: List[Type]): Type = {
    unique(new TypeRef(pre, sym, args) with UniqueType)
  }

  /** The canonical creator for implicit method types */
  def ImplicitMethodType(paramTypes: List[Type], resultType: Type): ImplicitMethodType =
    new ImplicitMethodType(paramTypes, resultType); // don't unique this!

  /** The canonical creator for implicit method types */
  def JavaMethodType(paramTypes: List[Type], resultType: Type): JavaMethodType =
    new JavaMethodType(paramTypes, resultType); // don't unique this!

  /** A creator for intersection type where intersections of a single type are
   *  replaced by the type itself, and repeated parent classes are merged. */
  def intersectionType(tps: List[Type], owner: Symbol): Type = tps match {
    case List(tp) =>
      tp
    case _ =>
       refinedType(tps, owner)
/*
      def merge(tps: List[Type]): List[Type] = tps match {
        case tp :: tps1 =>
          val tps1a = tps1 filter (.symbol.==(tp.symbol))
          val tps1b = tps1 filter (.symbol.!=(tp.symbol))
          mergePrefixAndArgs(tps1a, -1) match {
            case Some(tp1) => tp1 :: merge(tps1b)
            case None => throw new MalformedType(
              "malformed type: "+refinedType(tps, owner)+" has repeated parent class "+
              tp.symbol+" with incompatible prefixes or type arguments")
          }
        case _ => tps
      }
      refinedType(merge(tps), owner)
*/
  }

  /** A creator for intersection type where intersections of a single type are
   *  replaced by the type itself. */
  def intersectionType(tps: List[Type]): Type = tps match {
    case List(tp) => tp
    case _ => refinedType(tps, commonOwner(tps))
  }

  /** A creator for type applications */
  def appliedType(tycon: Type, args: List[Type]): Type = tycon match {
    case TypeRef(pre, sym, _) => typeRef(pre, sym, args)
    case PolyType(tparams, restpe) => restpe.subst(tparams, args)
    case ErrorType => tycon
    case _ =>
      System.out.println(tycon.getClass())
      System.out.println(tycon.$tag())
      throw new Error()
  }

// Hash consing --------------------------------------------------------------

  private var uniques: HashSet[AnyRef] = _
  private var uniqueRunId = NoRunId

  def uniqueTypeCount = uniques.size // for statistics

  private def unique[T <: AnyRef](tp: T): T = {
    if (uniqueRunId != currentRunId) {
      uniques = new HashSet(20000)
      uniqueRunId = currentRunId
    }
    val tp1 = uniques.findEntry(tp);
    if (tp1 == null) {
      uniques.addEntry(tp); tp
    } else {
      tp1.asInstanceOf[T]
    }
  }

// Helper Classes ---------------------------------------------------------

  /** A class expressing upper and lower bounds constraints
   *  for type variables, as well as their instantiations */
  class TypeConstraint(lo: List[Type], hi: List[Type]) {
    def this() = this(List(), List())
    var lobounds: List[Type] = lo
    var hibounds: List[Type] = hi
    var inst: Type = NoType

    def instantiate(tp: Type): boolean =
      if (lobounds.forall(.<:<(tp)) && hibounds.forall(tp.<:<)) {
        inst = tp; true
      } else false

    override def toString() =
      lobounds.mkString("[ _>:(", ",", ") ") +
      hibounds.mkString("| _<:(", ",", ") | _= ") + inst
  }

  /** A prototype for mapping a function over all possible types
   */
  abstract class TypeMap extends Function1[Type, Type] {
    // deferred inherited: def apply(tp: Type): Type

    private def cloneDecls(result: Type, tp: Type, decls: Scope): Type = {
      val syms1 = decls.toList
      for (val sym <- syms1)
        result.decls.enter(sym.cloneSymbol(result.symbol));
      val syms2 = result.decls.toList
      val resultThis = result.symbol.thisType
      for (val sym <- syms2)
        sym.setInfo(sym.info.substSym(syms1, syms2).substThis(tp.symbol, resultThis));
      result
    }

    /** Map this function over given type */
    def mapOver(tp: Type): Type = tp match {
      case ErrorType => tp
      case WildcardType => tp
      case NoType => tp
      case NoPrefix => tp
      case ThisType(_) => tp
      case ConstantType(_) => tp
      case SingleType(pre, sym) =>
        if (sym.isPackageClass) tp // short path
        else {
          val pre1 = this(pre)
          if (pre1 eq pre) tp
          else singleType(pre1, sym)
        }
      case SuperType(thistp, supertp) =>
        val thistp1 = this(thistp);
        val supertp1 = this(supertp);
        if ((thistp1 eq thistp) && (supertp1 eq supertp)) tp
        else SuperType(thistp1, supertp1)
      case TypeRef(pre, sym, args) =>
        val pre1 = this(pre)
        val args1 = List.mapConserve(args)(this)
        if ((pre1 eq pre) && (args1 eq args)) tp
        else typeRef(pre1, sym, args1)
      case TypeBounds(lo, hi) =>
        val lo1 = this(lo)
        val hi1 = this(hi)
        if ((lo1 eq lo) && (hi1 eq hi)) tp
        else TypeBounds(lo1, hi1)
      case BoundedWildcardType(bounds) =>
        val bounds1 = this(bounds)
        if (bounds1 eq bounds) tp
        else BoundedWildcardType(bounds1.asInstanceOf[TypeBounds])
      case RefinedType(parents, decls) =>
        val parents1 = List.mapConserve(parents)(this);
        val decls1 = mapOver(decls);
        if ((parents1 eq parents) && (decls1 eq decls)) tp
        else cloneDecls(refinedType(parents1, tp.symbol.owner), tp, decls1)
/*
      case ClassInfoType(parents, decls, clazz) =>
        val parents1 = List.mapConserve(parents)(this);
        val decls1 = mapOver(decls);
        if ((parents1 eq parents) && (decls1 eq decls)) tp
        else cloneDecls(ClassInfoType(parents1, new Scope(), clazz), tp, decls1)
*/
      case MethodType(paramtypes, result) =>
        val paramtypes1 = List.mapConserve(paramtypes)(this);
        val result1 = this(result);
        if ((paramtypes1 eq paramtypes) && (result1 eq result)) tp
        else if (tp.isInstanceOf[ImplicitMethodType]) ImplicitMethodType(paramtypes1, result1)
        else if (tp.isInstanceOf[JavaMethodType]) JavaMethodType(paramtypes1, result1)
        else MethodType(paramtypes1, result1)
      case PolyType(tparams, result) =>
        val tparams1 = mapOver(tparams);
        var result1 = this(result);
        if ((tparams1 eq tparams) && (result1 eq result)) tp
        else PolyType(tparams1, result1.substSym(tparams, tparams1))
      case OverloadedType(pre, alts) =>
        val pre1 = if (pre.isInstanceOf[ClassInfoType]) pre else this(pre);
        if (pre1 eq pre) tp
        else OverloadedType(pre1, alts)
      case AntiPolyType(pre, args) =>
        val pre1 = this(pre);
        val args1 = List.mapConserve(args)(this);
        if ((pre1 eq pre) && (args1 eq args)) tp
        else AntiPolyType(pre1, args1)
      case TypeVar(_, constr) =>
        if (constr.inst != NoType) this(constr.inst)
        else tp
      case _ =>
        tp
        // throw new Error("mapOver inapplicable for " + tp);
    }

    /** Map this function over given scope */
    private def mapOver(scope: Scope): Scope = {
      val elems = scope.toList
      val elems1 = mapOver(elems)
      if (elems1 eq elems) scope
      else new Scope(elems1)
    }

    /** Map this function over given list of symbols */
    private def mapOver(syms: List[Symbol]): List[Symbol] = {
      val infos = syms map (.info)
      val infos1 = List.mapConserve(infos)(this)
      if (infos1 eq infos) syms
      else {
        val syms1 = syms map (.cloneSymbol);
        (List.map2(syms1, infos1)
          ((sym1, info1) => sym1.setInfo(info1.substSym(syms, syms1))))
      }
    }
  }

  abstract class TypeTraverser extends TypeMap {
    def traverse(tp: Type): TypeTraverser //todo: return unit instead?
    def apply(tp: Type): Type = { traverse(tp); tp }
  }

  /** A map to compute the asSeenFrom method  */
  class AsSeenFromMap(pre: Type, clazz: Symbol) extends TypeMap {
    def apply(tp: Type): Type =
      if ((pre eq NoType) || (pre eq NoPrefix) || !clazz.isClass) tp
      else tp match {
        case ThisType(sym) =>
          def toPrefix(pre: Type, clazz: Symbol): Type =
            if ((pre eq NoType) || (pre eq NoPrefix) || !clazz.isClass) tp
            else if ((sym isNonBottomSubClass clazz) &&
                     (pre.widen.symbol isNonBottomSubClass sym)) pre
            else toPrefix(pre.baseType(clazz).prefix, clazz.owner);
          toPrefix(pre, clazz)
        case TypeRef(prefix, sym, args) if (sym.isTypeParameter) =>
          def toInstance(pre: Type, clazz: Symbol): Type =
            if ((pre eq NoType) || (pre eq NoPrefix) || !clazz.isClass) tp
            else {
              val symclazz = sym.owner;
              def throwError =
                throw new Error("" + tp + " in " + symclazz +
                                " cannot be instantiated from " + pre.widen);
              def instParam(ps: List[Symbol], as: List[Type]): Type =
                if (ps.isEmpty) throwError
                else if (sym eq ps.head) as.head
                else instParam(ps.tail, as.tail);
              if (symclazz == clazz && (pre.widen.symbol isNonBottomSubClass symclazz))
                pre.baseType(symclazz) match {
                  case TypeRef(_, basesym, baseargs) =>
//                    System.out.println("instantiating " + sym + " from " + basesym + " with " + basesym.typeParams + " and " + baseargs);//DEBUG
                    if (basesym.typeParams.length != baseargs.length)
                      throw new TypeError(
                        "something is wrong (wrong class file?): "+basesym+
                        " with type parameters "+
                        basesym.typeParams.map(.name).mkString("[",",","]")+
                        " gets applied to arguments "+baseargs.mkString("(",",",")"))
                    instParam(basesym.typeParams, baseargs);
                  case _ =>
                    throwError
                }
              else toInstance(pre.baseType(clazz).prefix, clazz.owner)
            }
          toInstance(pre, clazz)
        case _ =>
          mapOver(tp)
      }
  }

  /** A base class to compute all substitutions */
  abstract class SubstMap[T](from: List[Symbol], to: List[T]) extends TypeMap {

    /** Are sym1, sym1 the same. Can be tunded by subclasses */
    protected def matches(sym: Symbol, sym1: Symbol): boolean = sym eq sym1;

    /** Map target to type, can be tuned by subclasses */
    protected def toType(fromtp: Type, t: T): Type;

    def apply(tp: Type): Type = {
      def subst(sym: Symbol, from: List[Symbol], to: List[T]): Type =
        if (from.isEmpty) tp
        else if (matches(from.head, sym)) toType(tp, to.head)
        else subst(sym, from.tail, to.tail);
      tp match {
        case TypeRef(NoPrefix, sym, _) =>
          subst(sym, from, to)
        case SingleType(NoPrefix, sym) =>
          subst(sym, from, to)
        case PolyType(tparams, restp) =>
          assert(!(tparams exists (from contains)));
          mapOver(tp)
        case _ =>
          mapOver(tp)
      }
    }
  }

  /** A map to implement the substSym method */
  class SubstSymMap(from: List[Symbol], to: List[Symbol])
  extends SubstMap(from, to) {
    protected def toType(fromtp: Type, sym: Symbol) = fromtp match {
      case TypeRef(pre, _, args) => typeRef(pre, sym, args)
      case SingleType(pre, _) => singleType(pre, sym)
    }
  }

  /** A map to implement the subst method */
  class SubstTypeMap(from: List[Symbol], to: List[Type])
  extends SubstMap(from, to) {
    protected def toType(fromtp: Type, tp: Type) = tp
  }

  /** A map to implement the substThis method */
  class SubstThisMap(from: Symbol, to: Type) extends TypeMap {
    def apply(tp: Type): Type = tp match {
      case ThisType(sym) if (sym == from) => to
      case _ => mapOver(tp)
    }
  }

  class SubstSuperMap(from: Type, to: Type) extends TypeMap {
    def apply(tp: Type): Type = if (tp eq from) to else mapOver(tp)
  }

  /** A map to convert every occurrence of a wildcard type to a fresh
   *  type variable */
  object wildcardToTypeVarMap extends TypeMap {
    def apply(tp: Type): Type = tp match {
      case WildcardType =>
        TypeVar(tp, new TypeConstraint)
      case BoundedWildcardType(bounds) =>
        TypeVar(tp, new TypeConstraint(List(bounds.lo), List(bounds.hi)))
      case _ =>
        mapOver(tp)
    }
  }

  /** A map to implement the contains method */
  class ContainsTraverser(sym: Symbol) extends TypeTraverser {
    var result = false;
    def traverse(tp: Type): ContainsTraverser = {
      if (!result) {
        tp match {
          case TypeRef(_, sym1, _) if (sym == sym1) => result = true
          case SingleType(_, sym1) if (sym == sym1) => result = true
          case _ => mapOver(tp)
        }
      }
      this
    }
  }

  /** A map to implement the contains method */
  object ErroneousTraverser extends TypeTraverser {
    var result: boolean = _
    def traverse(tp: Type): TypeTraverser = {
      if (!result) {
        result = tp.isError
        mapOver(tp)
      }
      this
    }
  }

  /** A map to compute the most deeply nested owner that contains all the symbols
   *  of thistype or prefixless typerefs/singletype occurrences in given type */
  object commonOwnerMap extends TypeMap {
    var result: Symbol = _;
    def init = { result = NoSymbol }
    def apply(tp: Type): Type = {
      assert(tp != null);
      tp match {
        case ThisType(sym) =>
          register(sym);
        case TypeRef(NoPrefix, sym, args) =>
          register(sym.owner); args foreach {arg => apply(arg); ()}
        case SingleType(NoPrefix, sym) =>
          register(sym.owner);
        case _ =>
          mapOver(tp)
      }
      tp
    }
    private def register(sym: Symbol): unit = {
      while (result != NoSymbol && sym != result && !(sym isNestedIn result))
        result = result.owner;
    }
  }

  object adaptToNewRunMap extends TypeMap {
    private def adaptToNewRun(pre: Type, sym: Symbol): Symbol = {
      if (sym.isModuleClass && !phase.flatClasses) adaptToNewRun(pre, sym.sourceModule).moduleClass;
      else if ((pre eq NoPrefix) || (pre eq NoType) || sym.owner.isPackageClass) sym
      else {
        var rebind0 = pre.member(sym.name)
        /** The two symbols have the same fully qualified name */
        def corresponds(sym1: Symbol, sym2: Symbol): boolean =
          sym1.name == sym2.name && (sym1.isPackageClass || corresponds(sym1.owner, sym2.owner))
        if (!corresponds(sym.owner, rebind0.owner)) {
          if (settings.debug.value) Console.println("ADAPT1 pre = "+pre+", sym = "+sym+sym.locationString+", rebind = "+rebind0+rebind0.locationString)
          val bcs = pre.baseClasses.dropWhile(bc => !corresponds(bc, sym.owner));
          assert(!bcs.isEmpty)
          rebind0 = pre.baseType(bcs.head).member(sym.name)
          if (settings.debug.value) Console.println("ADAPT2 pre = "+pre+", sym = "+sym+sym.locationString+", rebind = "+rebind0+rebind0.locationString)
        }
        val rebind = rebind0.suchThat(sym => sym.isType || sym.isStable)
        if (rebind == NoSymbol) {
          if (settings.debug.value) Console.println("" + phase + " " + phase.flatClasses+sym.owner+sym.name)
          throw new MalformedType(pre, sym.name.toString())
        }
        rebind
      }
    }
    def apply(tp: Type): Type = tp match {
      case ThisType(sym) if (sym.isModuleClass) =>
        val sym1 = adaptToNewRun(sym.owner.thisType, sym);
        if (sym1 == sym) tp else ThisType(sym1)
      case SingleType(pre, sym) =>
        if (sym.isPackage) tp
        else {
          val pre1 = this(pre)
          val sym1 = adaptToNewRun(pre1, sym)
          if ((pre1 eq pre) && (sym1 eq sym)) tp
          else singleType(pre1, sym1)
        }
      case TypeRef(pre, sym, args) =>
        if (sym.isPackageClass) tp
        else {
          val pre1 = this(pre)
          val args1 = List.mapConserve(args)(this)
          val sym1 = adaptToNewRun(pre1, sym)
          if ((pre1 eq pre) && (sym1 eq sym) && (args1 eq args)/* && sym.isExternal*/) tp
          else typeRef(pre1, sym1, args1)
        }
      case PolyType(tparams, restp) =>
        val restp1 = this(restp)
        if (restp1 eq restp) tp
        else PolyType(tparams, restp1)
      case ClassInfoType(parents, decls, clazz) =>
        val parents1 = List.mapConserve(parents)(this)
        if (parents1 eq parents) tp
        else ClassInfoType(parents1, decls, clazz)
      case RefinedType(parents, decls) =>
        val parents1 = List.mapConserve(parents)(this)
        if (parents1 eq parents) tp
        else refinedType(parents1, tp.symbol.owner, decls)
      case SuperType(_, _) => mapOver(tp)
      case TypeBounds(_, _) => mapOver(tp)
      case MethodType(_, _) => mapOver(tp)
      case TypeVar(_, _) => mapOver(tp)
      case _ => tp
    }
  }

// Helper Methods  -------------------------------------------------------------

  final def isValid(period: Period): boolean =
    period != 0 && runId(period) == currentRunId && {
      val pid = phaseId(period)
      if (phase.id > pid) infoTransformers.nextFrom(pid).pid >= phase.id
      else infoTransformers.nextFrom(phase.id).pid >= pid
    }

  final def isValidForBaseClasses(period: Period): boolean = {
    def noChangeInBaseClasses(it: InfoTransformer, limit: Phase#Id): boolean = (
      it.pid >= limit ||
      !it.changesBaseClasses && noChangeInBaseClasses(it.next, limit)
    );
    period != 0 && runId(period) == currentRunId && {
      val pid = phaseId(period)
      if (phase.id > pid) noChangeInBaseClasses(infoTransformers.nextFrom(pid), phase.id)
      else noChangeInBaseClasses(infoTransformers.nextFrom(phase.id), pid)
    }
  }

  /** Do tp1 and tp2 denote equivalent types? */
  def isSameType(tp1: Type, tp2: Type): boolean = {
    Pair(tp1, tp2) match {
      case Pair(ErrorType, _) => true
      case Pair(WildcardType, _) => true
      case Pair(_, ErrorType) => true
      case Pair(_, WildcardType) => true

      case Pair(NoType, _) => false
      case Pair(NoPrefix, _) => tp2.symbol.isPackageClass
      case Pair(_, NoType) => false
      case Pair(_, NoPrefix) => tp1.symbol.isPackageClass

      case Pair(ThisType(sym1), ThisType(sym2))
      if (sym1 == sym2) =>
        true
      case Pair(SingleType(pre1, sym1), SingleType(pre2, sym2))
      if ((sym1 == sym2) && (pre1 =:= pre2)) =>
        true
/*
      case Pair(SingleType(pre1, sym1), ThisType(sym2))
      if (sym1.isModule &&
          sym1.moduleClass == sym2 &&
          pre1 =:= sym2.owner.thisType) =>
        true
      case Pair(ThisType(sym1), SingleType(pre2, sym2))
      if (sym2.isModule &&
          sym2.moduleClass == sym1 &&
          pre2 =:= sym1.owner.thisType) =>
        true
*/
      case Pair(ConstantType(value1), ConstantType(value2)) =>
        value1 == value2
      case Pair(TypeRef(pre1, sym1, args1), TypeRef(pre2, sym2, args2)) =>
        sym1 == sym2 && (phase.erasedTypes || pre1 =:= pre2) && isSameTypes(args1, args2)
      case Pair(RefinedType(parents1, ref1), RefinedType(parents2, ref2)) =>
        def isSubScope(s1: Scope, s2: Scope): boolean = s2.toList.forall {
          sym2 =>
            val sym1 = s1.lookup(sym2.name);
            sym1 != NoSymbol &&
            sym1.info =:= sym2.info.substThis(sym2.owner, sym1.owner.thisType)
        }
        //System.out.println("is same? " + tp1 + " " + tp2 + " " + tp1.symbol.owner + " " + tp2.symbol.owner)//DEBUG
        isSameTypes(parents1, parents2) && isSubScope(ref1, ref2) && isSubScope(ref2, ref1)
      case Pair(MethodType(pts1, res1), MethodType(pts2, res2)) =>
        (pts1.length == pts2.length &&
         isSameTypes(pts1, pts2) &&
         res1 =:= res2 &&
         tp1.isInstanceOf[ImplicitMethodType] == tp2.isInstanceOf[ImplicitMethodType])
      case Pair(PolyType(tparams1, res1), PolyType(tparams2, res2)) =>
        (tparams1.length == tparams2.length &&
         List.forall2(tparams1, tparams2)
           ((p1, p2) => p1.info =:= p2.info.substSym(tparams2, tparams1)) &&
         res1 =:= res2.substSym(tparams2, tparams1))
      case Pair(TypeBounds(lo1, hi1), TypeBounds(lo2, hi2)) =>
        lo1 =:= lo2 && hi1 =:= hi2
      case Pair(BoundedWildcardType(bounds), _) =>
        bounds containsType tp2
      case Pair(_, BoundedWildcardType(bounds)) =>
        bounds containsType tp1
      case Pair(TypeVar(_, constr1), _) =>
        if (constr1.inst != NoType) constr1.inst =:= tp2
        else constr1 instantiate (wildcardToTypeVarMap(tp2))
      case Pair(_, TypeVar(_, constr2)) =>
        if (constr2.inst != NoType) tp1 =:= constr2.inst
        else constr2 instantiate (wildcardToTypeVarMap(tp1))
      case _ =>
        if (tp1.isStable && tp2.isStable) {
          var origin1 = tp1
          while (origin1.singleDeref.isStable) origin1 = origin1.singleDeref
          var origin2 = tp2
          while (origin2.singleDeref.isStable) origin2 = origin2.singleDeref
          ((origin1 ne tp1) || (origin2 ne tp2)) && (origin1 =:= origin2)
        } else false
    }
  }

  /** Are tps1 and tps2 lists of pairwise equivalent types? */
  def isSameTypes(tps1: List[Type], tps2: List[Type]): boolean = (
    tps1.length == tps2.length &&
    List.forall2(tps1, tps2)((tp1, tp2) => tp1 =:= tp2)
  );

  var subtypecount = 0;
  def isSubType(tp1: Type, tp2: Type): boolean = {
    subtypecount = subtypecount + 1;
    if (subtypecount == 20) throw new Error("recursive <:<");
    val result = isSubType0(tp1, tp2);
    subtypecount = subtypecount - 1;
    result
  }

  /** Does tp1 conform to tp2? */
  def isSubType0(tp1: Type, tp2: Type): boolean = {
    Pair(tp1, tp2) match {
      case Pair(ErrorType, _)    => true
      case Pair(WildcardType, _) => true
      case Pair(_, ErrorType)    => true
      case Pair(_, WildcardType) => true

      case Pair(NoType, _)   => false
      case Pair(NoPrefix, _) => tp2.symbol.isPackageClass
      case Pair(_, NoType)   => false
      case Pair(_, NoPrefix) => tp1.symbol.isPackageClass

      case Pair(ThisType(_), ThisType(_))           => tp1 =:= tp2
      case Pair(ThisType(_), SingleType(_, _))      => tp1 =:= tp2
      case Pair(SingleType(_, _), ThisType(_))      => tp1 =:= tp2
      case Pair(SingleType(_, _), SingleType(_, _)) => tp1 =:= tp2
      case Pair(ConstantType(_), ConstantType(_))   => tp1 =:= tp2

      case Pair(TypeRef(pre1, sym1, args1), TypeRef(pre2, sym2, args2)) =>
        //System.out.println("isSubType " + tp1 + " " + tp2);//DEBUG
        def isSubArgs(tps1: List[Type], tps2: List[Type],
                      tparams: List[Symbol]): boolean = (
          tps1.isEmpty && tps2.isEmpty
          ||
          !tps1.isEmpty && !tps2.isEmpty &&
          (tparams.head.isCovariant || (tps2.head <:< tps1.head)) &&
          (tparams.head.isContravariant || (tps1.head <:< tps2.head)) &&
          isSubArgs(tps1.tail, tps2.tail, tparams.tail)
        );
        (sym1 == sym2 && (pre1 <:< pre2) && isSubArgs(args1, args2, sym1.typeParams)
         ||
         sym1.isAbstractType && !(tp1 =:= tp1.bounds.hi) && (tp1.bounds.hi <:< tp2)
         ||
         sym2.isAbstractType && !(tp2 =:= tp2.bounds.lo) && (tp1 <:< tp2.bounds.lo)
         ||
//         sym2 == NonNullClass && tp1.isNonNull
//         ||
         sym2.isClass &&
           ({ val base = tp1 baseType sym2; !(base eq tp1) && (base <:< tp2) })
         ||
         sym1 == AllClass
         ||
         // System.out.println("last chance " + sym1 + " " + sym2 + " " + sym2.isClass + " " (sym2 isSubClass ObjectClass))
         sym1 == AllRefClass && sym2.isClass && (sym2 isSubClass ObjectClass))
      case Pair(MethodType(pts1, res1), MethodType(pts2, res2)) =>
        (pts1.length == pts2.length &&
         matchingParams(pts1, pts2, tp2.isInstanceOf[JavaMethodType]) &&
         (res1 <:< res2) &&
         tp1.isInstanceOf[ImplicitMethodType] == tp2.isInstanceOf[ImplicitMethodType])
      case Pair(PolyType(tparams1, res1), PolyType(tparams2, res2)) =>
        (tparams1.length == tparams2.length &&
         List.forall2(tparams1, tparams2)
           ((p1, p2) => p2.info.substSym(tparams2, tparams1) <:< p1.info) &&
         res1 <:< res2.substSym(tparams2, tparams1))
      case Pair(TypeBounds(lo1, hi1), TypeBounds(lo2, hi2)) =>
        lo2 <:< lo1 && hi1 <:< hi2
      case Pair(BoundedWildcardType(bounds), _) =>
        bounds.lo <:< tp2
      case Pair(_, BoundedWildcardType(bounds)) =>
        tp1 <:< bounds.hi
      case Pair(_, TypeVar(_, constr2)) =>
        if (constr2.inst != NoType) tp1 <:< constr2.inst
        else { constr2.lobounds = tp1 :: constr2.lobounds; true }
      case Pair(TypeVar(_, constr1), _) =>
        if (constr1.inst != NoType) constr1.inst <:< tp2
        else { constr1.hibounds = tp2 :: constr1.hibounds; true }
      case Pair(_, RefinedType(parents2, ref2)) =>
        (parents2 forall tp1.<:<) && (ref2.toList forall tp1.specializes) &&
        (!parents2.exists(.symbol.isAbstractType) || tp1.symbol != AllRefClass)
      case Pair(RefinedType(parents1, ref1), _) =>
        parents1 exists (.<:<(tp2))
      /* todo: replace following with
      case Pair(ThisType(_), _)
         | Pair(SingleType(_, _), _)
         | Pair(ConstantType(_), _) =>
         once patern matching bug is fixed */
      case Pair(ThisType(_), _) => tp1.singleDeref <:< tp2
      case Pair(SingleType(_, _), _) => tp1.singleDeref <:< tp2
      case Pair(ConstantType(_), _) => tp1.singleDeref <:< tp2

      case Pair(TypeRef(pre1, sym1, args1), _) =>
        (sym1 == AllClass && tp2 <:< AnyClass.tpe
         ||
         sym1 == AllRefClass && tp2.isInstanceOf[SingletonType] && (tp1 <:< tp2.widen))
/*         ||
         sym1 == AllRefClass && tp2.symbol != AllClass &&
         tp2 <:< AnyRefClass.tpe))
           if (X) tp2.isNullable
           else tp2.symbol != AllClass && tp2 <:< AnyRefClass.tpe))*/
      case _ =>
        false
    }
  }

  /** Are tps1 and tps2 lists of equal length such that all elements
   *  of tps1 conform to corresponding elements of tps2? */
  def isSubTypes(tps1: List[Type], tps2: List[Type]): boolean = (
    tps1.length == tps2.length &&
    List.forall2(tps1, tps2)((tp1, tp2) => tp1 <:< tp2)
  );

  /** Does type `tp' implement symbol `sym' with same or stronger type?
   *  Exact only if `sym' is a member of some refinement type, otherwise
   *  we might return false negatives */
  def specializesSym(tp: Type, sym: Symbol): boolean = (
    tp.symbol == AllClass ||
    tp.symbol == AllRefClass && (sym.owner isSubClass ObjectClass) ||
    (tp.nonPrivateMember(sym.name).alternatives exists
      (alt => sym == alt || specializesSym(tp.narrow, alt, sym.owner.thisType, sym)))
   );

  /** Does member `sym1' of `tp1' have a stronger type than member `sym2' of `tp2'? */
  private def specializesSym(tp1: Type, sym1: Symbol, tp2: Type, sym2: Symbol): boolean = {
    //System.out.println("specializes "+tp1+"."+sym1+" "+tp2+"."+sym2)//DEBUG
    val info1 = tp1.memberInfo(sym1);
    val info2 = tp2.memberInfo(sym2).substThis(tp2.symbol, tp1);
    sym2.isTerm && (info1 <:< info2) ||
    sym2.isAbstractType && info2.bounds.containsType(info1) ||
    sym2.isAliasType && tp2.memberType(sym2).substThis(tp2.symbol, tp1) =:= tp1.memberType(sym1)
  }

  /** A function implementing tp1 matches tp2 */
  private def matchesType(tp1: Type, tp2: Type): boolean = Pair(tp1, tp2) match {
    case Pair(MethodType(pts1, res1), MethodType(pts2, res2)) =>
      (matchingParams(pts1, pts2, tp2.isInstanceOf[JavaMethodType]) && (res1 matches res2) &&
       tp1.isInstanceOf[ImplicitMethodType] == tp2.isInstanceOf[ImplicitMethodType])
    case Pair(PolyType(tparams1, res1), PolyType(tparams2, res2)) =>
      (tparams1.length == tparams2.length &&
       (res1 matches res2.substSym(tparams2, tparams1)))
    case Pair(PolyType(List(), rtp1), MethodType(List(), rtp2)) => matchesType(rtp1, rtp2)
    case Pair(MethodType(List(), rtp1), PolyType(List(), rtp2)) => matchesType(rtp1, rtp2)
    case Pair(PolyType(List(), rtp1), _) => matchesType(rtp1, tp2)
    case Pair(_, PolyType(List(), rtp2)) => matchesType(tp1, rtp2)
    case Pair(MethodType(_, _), _) => false
    case Pair(PolyType(_, _), _)   => false
    case Pair(_, MethodType(_, _)) => false
    case Pair(_, PolyType(_, _))   => false
    case _ =>
      !phase.erasedTypes || tp1 =:= tp2
  }

  /** Are tps1 and tps2 lists of pairwise equivalent types? */
  private def matchingParams(tps1: List[Type], tps2: List[Type], tps2isJava: boolean): boolean = (
    tps1.length == tps2.length &&
    List.forall2(tps1, tps2)((tp1, tp2) =>
      (tp1 =:= tp2) || tps2isJava & tp1.symbol == ObjectClass && tp2.symbol == AnyClass)
  );

  /** Prepend type `tp' to closure `cl' */
  private def addClosure(tp: Type, cl: Array[Type]): Array[Type] = {
    val cl1 = new Array[Type](cl.length + 1);
    cl1(0) = tp;
    System.arraycopy(cl, 0, cl1, 1, cl.length);
    cl1
  }

// Lubs and Glbs ---------------------------------------------------------

  private val recLimit = 10
  private var recCount = 0
  private var giveUp: boolean = _

  /** Return op(tps), but give up if level of recursion is greater than
   *  recLimit */
  private def limitRecursion(tps: List[Type], boundkind: String,
                             op: List[Type] => Type): Type =
    if (recCount == recLimit) {
      giveUp = true;
      AnyClass.tpe
    } else {
      if (recCount == 0) giveUp = false;
      val result = try {
        recCount = recCount + 1;
        op(tps)
      } finally {
        recCount = recCount - 1
      }
      if (recCount == 0 && giveUp) {
        throw new TypeError("failure to compute " + boundkind +
                            " bound of types " +
                            tps.mkString("", " and ", ";\n") +
                            "an approximation is: " + result + ";\n" +
                            "additional type annotations are needed");
      }
      result
    }

  /** The greatest sorted upwards closed lower bound of a list of lists of
   *  types relative to the following ordering &lt;= between lists of types:
   *
   *    xs &lt;= ys   iff   forall y in ys exists x in xs such that x &lt;: y
   *
   *  @See closure  for a definition of sorted and upwards closed.
   */
  private def glbList(tss: List[List[Type]]): List[Type] = {
    val tss1 = tss filter (ts => !ts.isEmpty);
    if (tss1.isEmpty) List()
    else if (tss1.tail.isEmpty) tss.head
    else {
      val ts0 = tss1 map (.head);
      val sym = minSym(ts0);
      val ts1 = elimSuper(ts0 filter (.symbol.==(sym)));
      mergePrefixAndArgs(ts1, -1) match {
        case Some(tp0) =>
          tp0 :: glbList(tss1 map (ts => if (ts.head.symbol == sym) ts.tail else ts))
        case None =>
          throw new MalformedClosure(ts1)
      }
    }
  }

  /** The greatest sorted upwards closed lower bound of a list of closures.
   *  @See glbList for more explanations.
   */
  private def glbArray(tss: List[Array[Type]]): Array[Type] = {
    val tss1 = tss map { ts: Array[Type] => List.fromArray(ts) }
    val glbs = glbList(tss1);
    val result = new Array[Type](glbs.length);
    var i = 0;
    for (val x <- glbs.elements) { result(i) = x; i = i + 1; }
    result;
    // Predef.Array(glbs: _*);
  }

  /** The least sorted upwards closed upper bound of a non-empty list
   *  of lists of types.
   *  @See glbList for more explanations. */
  private def lubList(tss: List[List[Type]]): List[Type] =
    if (tss.tail.isEmpty) tss.head
    else if (tss exists (.isEmpty)) List()
    else {
      val ts0 = tss map (.head);
      val sym = minSym(ts0);
      if (ts0 forall (t => t.symbol == sym))
        mergePrefixAndArgs(elimSub(ts0), 1).toList ::: lubList(tss map (.tail))
      else
        lubList(tss map (ts => if (ts.head.symbol == sym) ts.tail else ts))
    }

  /** The least sorted upwards closed upper bound of a non-empty list
   *  of closures.
   *  @See lubList for more explanations. */
  private def lubArray(tss: List[Array[Type]]): Array[Type] = {
    var lubs = lubList(tss map { ts: Array[Type] => List.fromArray(ts) });
    var arr = new Array[Type](lubs.length);
    var i = 0;
    while (i < arr.length) {
      arr(i) = lubs.head;
      i = i + 1;
      lubs = lubs.tail
    }
    arr
    // todo: replace by  Predef.Array(lubs: _* )
  }

  /** The minimal symbol (wrt Symbol.isLess) of a list of types */
  private def minSym(tps: List[Type]): Symbol =
    (tps.head.symbol /: tps.tail) {
      (sym1, tp2) => if (tp2.symbol isLess sym1) tp2.symbol else sym1
    }

  /** A minimal type list which has a given array of types as its closure */
  def spanningTypes(ts: List[Type]): List[Type] = ts match {
    case List() => List()
    case first :: rest =>
      first :: spanningTypes(
        rest filter (t => !first.symbol.isSubClass(t.symbol)))
  }

  /** Eliminate from list of types all elements which are a supertype
   *  of some other element of the list. */
  private def elimSuper(ts: List[Type]): List[Type] = ts match {
    case List() => List()
    case t :: ts1 =>
      val rest = ts1 filter (t1 => !(t <:< t1));
      if (rest exists (t1 => t1 <:< t)) rest else t :: rest
  }

  /** Eliminate from list of types all elements which are a subtype
   *  of some other element of the list. */
  private def elimSub(ts: List[Type]): List[Type] =  ts match {
    case List() => List()
    case t :: ts1 =>
      val rest = ts1 filter (t1 => !(t1 <:< t));
      if (rest exists (t1 => t <:< t1)) rest else t :: rest
  }

  /** The least upper bound wrt &lt;:&lt; of a list of types */
  def lub(ts: List[Type]): Type = {
    def lub0(ts0: List[Type]): Type = elimSub(ts0 map (.deconst)) match {
      case List() => AllClass.tpe
      case List(t) => t
      case ts @ PolyType(tparams, _) :: _ =>
        PolyType(
          List.map2(tparams, List.transpose(matchingBounds(ts, tparams)))
            ((tparam, bounds) => tparam.cloneSymbol.setInfo(glb(bounds))),
          lub0(matchingInstTypes(ts, tparams)))
      case ts @ MethodType(pts, _) :: rest =>
        MethodType(pts, lub0(matchingRestypes(ts, pts)))
      case ts @ TypeBounds(_, _) :: rest =>
        TypeBounds(glb(ts map (.bounds.lo)), lub(ts map (.bounds.hi)))
      case ts =>
        val closures: List[Array[Type]] = ts map (.closure);
        val lubBaseTypes: Array[Type] = lubArray(closures);
        //log("closures = " + (closures map (cl => List.fromArray(cl))) + ", lubbases = " + List.fromArray(lubBaseTypes));//DEBUG
        val lubParents = spanningTypes(List.fromArray(lubBaseTypes));
        val lubOwner = commonOwner(ts);
        val lubBase = intersectionType(lubParents, lubOwner);
        if (phase.erasedTypes) lubBase
        else {
          val lubType = refinedType(lubParents, lubOwner);
          val lubThisType = lubType.symbol.thisType;
          val narrowts = ts map (.narrow);
          def lubsym(proto: Symbol): Symbol = {
            val prototp = lubThisType.memberInfo(proto);
            val syms = narrowts map (t =>
              t.nonPrivateMember(proto.name).suchThat(sym =>
                sym.tpe matches prototp.substThis(lubThisType.symbol, t)));
            if (syms contains NoSymbol) NoSymbol
            else {
              val symtypes =
                (List.map2(narrowts, syms)
                   ((t, sym) => t.memberInfo(sym).substThis(t.symbol, lubThisType)));
              if (proto.isTerm)
                proto.cloneSymbol(lubType.symbol).setInfo(lub(symtypes))
              else if (symtypes.tail forall (symtypes.head =:=))
                proto.cloneSymbol(lubType.symbol).setInfo(symtypes.head)
              else {
                def lubBounds(bnds: List[TypeBounds]): TypeBounds =
                  TypeBounds(glb(bnds map (.lo)), lub(bnds map (.hi)));
                proto.owner.newAbstractType(proto.pos, proto.name)
                  .setInfo(lubBounds(symtypes map (.bounds)))
              }
            }
          }
          def refines(tp: Type, sym: Symbol): boolean = {
            val syms = tp.nonPrivateMember(sym.name).alternatives;
            !syms.isEmpty && (syms forall (alt =>
              // todo alt != sym is strictly speaking not correct, but without it we lose
              // efficiency.
              alt != sym && !specializesSym(lubThisType, sym, tp, alt)))
          }
          for (val sym <- lubBase.nonPrivateMembers)
            // add a refinement symbol for all non-class members of lubBase
            // which are refined by every type in ts.
            if (!sym.isClass && !sym.isConstructor && (narrowts forall (t => refines(t, sym))))
              try {
                addMember(lubThisType, lubType, lubsym(sym))
              } catch {
                case ex: NoCommonType =>
              }
          if (lubType.decls.isEmpty) lubBase else lubType;
        }
    }
    if (settings.debug.value) {
      log(indent + "lub of " + ts);//debug
      indent = indent + "  ";
    }
    val res = limitRecursion(ts, "least upper", lub0);
    if (settings.debug.value) {
      indent = indent.substring(0, indent.length() - 2);
      log(indent + "lub of " + ts + " is " + res);//debug
    }
    res
  }

  def glb(ts: List[Type]): Type = glb(ts, !phase.erasedTypes);
  def glbNoRefinement(ts: List[Type]): Type = glb(ts, false);

  /** The greatest lower bound wrt &lt;:&lt; of a list of types */
  private def glb(ts: List[Type], computeRefinement: boolean): Type = {
    def glb0(ts0: List[Type]): Type = elimSuper(ts0 map (.deconst)) match {
      case List() => AnyClass.tpe
      case List(t) => t
      case ts @ PolyType(tparams, _) :: _ =>
        PolyType(
          List.map2(tparams, List.transpose(matchingBounds(ts, tparams)))
          ((tparam, bounds) => tparam.cloneSymbol.setInfo(lub(bounds))),
          glb0(matchingInstTypes(ts, tparams)))
      case ts @ MethodType(pts, _) :: rest =>
        MethodType(pts, glb0(matchingRestypes(ts, pts)))
      case ts @ TypeBounds(_, _) :: rest =>
        TypeBounds(lub(ts map (.bounds.lo)), glb(ts map (.bounds.hi)))
      case ts =>
        try {
          val glbOwner = commonOwner(ts);
          val glbBase = intersectionType(ts, glbOwner);
          if (computeRefinement) {
            val glbType = refinedType(ts, glbOwner);
            val glbThisType = glbType.symbol.thisType;
            def glbsym(proto: Symbol): Symbol = {
              val prototp = glbThisType.memberInfo(proto);
              val syms = for (
                val t <- ts;
                val alt <- t.nonPrivateMember(proto.name).alternatives;
                glbThisType.memberInfo(alt) matches prototp) yield alt;
              val symtypes = syms map glbThisType.memberInfo;
              assert(!symtypes.isEmpty);
              proto.cloneSymbol(glbType.symbol).setInfo(
                if (proto.isTerm) glb(symtypes)
                else {
                  def isTypeBound(tp: Type) = tp match {
                    case TypeBounds(_, _) => true
                    case _ => false
                  }
                  def glbBounds(bnds: List[Type]): TypeBounds = {
                    val lo = lub(bnds map (.bounds.lo));
                    val hi = glb(bnds map (.bounds.hi));
                    if (lo <:< hi) TypeBounds(lo, hi)
                    else throw new MalformedClosure(bnds)
                  }
                  val symbounds = symtypes filter isTypeBound;
                  var result: Type =
                    if (symbounds.isEmpty)
                      TypeBounds(AllClass.tpe, AnyClass.tpe)
                    else glbBounds(symbounds);
                  for (val t <- symtypes; !isTypeBound(t))
                    if (result.bounds containsType t) result = t
                    else throw new MalformedClosure(symtypes);
                  result
                })
            }
            for (val t <- ts; val sym <- t.nonPrivateMembers)
              if (!sym.isClass && !sym.isConstructor && !(glbThisType specializes sym))
                try {
                  addMember(glbThisType, glbType, glbsym(sym))
                } catch {
                  case ex: NoCommonType =>
                }
            if (glbType.decls.isEmpty) glbBase else glbType
          } else {
            glbBase
          }
        } catch {
          case _: MalformedClosure =>
            if (ts forall (t => AllRefClass.tpe <:< t)) AllRefClass.tpe
            else AllClass.tpe
        }
    }
    if (settings.debug.value) {
      log(indent + "glb of " + ts);//debug
      indent = indent + "  ";
    }
    val res = limitRecursion(ts, "greatest lower", glb0);
    if (settings.debug.value) {
      indent = indent.substring(0, indent.length() - 2);
      log(indent + "glb of " + ts + " is " + res);//debug
    }
    res
  }

  /** The most deeply nested owner that contains all the symbols
   *  of thistype or prefixless typerefs/singletype occurrences in given type */
  private def commonOwner(t: Type): Symbol = {
    commonOwnerMap.init;
    commonOwnerMap.apply(t);
    commonOwnerMap.result
  }

  /** The most deeply nested owner that contains all the symbols
   *  of thistype or prefixless typerefs/singletype occurrences in given list of types */
  private def commonOwner(tps: List[Type]): Symbol = {
    if (settings.debug.value) log("computing common owner of types " + tps);//debug
    commonOwnerMap.init;
    tps foreach { tp => commonOwnerMap.apply(tp); () }
    commonOwnerMap.result
  }

  /** Compute lub (if variance == 1) or glb (if variance == -1) of given list of types
   *  `tps'. All types in `tps' are typerefs or singletypes with the same symbol.
   *  Return Some(x) if the computation succeeds with result `x'.
   *  Return None if the computuation fails.
   */
  private def mergePrefixAndArgs(tps: List[Type], variance: int): Option[Type] = tps match {
    case List(tp) =>
      Some(tp)
    case TypeRef(_, sym, _) :: rest =>
      val pres = tps map (.prefix);
      val pre = if (variance == 1) lub(pres) else glb(pres);
      val argss = tps map (.typeArgs);
      val args =
        (List.map2(sym.typeParams, List.transpose(argss))
           ((tparam, as) =>
             if (tparam.variance == variance) lub(as)
             else if (tparam.variance == -variance) glb(as)
             else NoType));
      try {
        if (args contains NoType) None
        else Some(typeRef(pre, sym, args))
      } catch {
        case ex: MalformedType => None
      }
    case SingleType(_, sym) :: rest =>
      val pres = tps map (.prefix);
      val pre = if (variance == 1) lub(pres) else glb(pres);
      try {
        Some(singleType(pre, sym))
      } catch {
        case ex: MalformedType => None
      }
  }

  /** Make symbol `sym' a member of scope `tp.decls' where `thistp' is the narrowed
   *  owner type of the scope */
  def addMember(thistp: Type, tp: Type, sym: Symbol): unit = {
    if (settings.debug.value) log("add member " + sym);//debug
    if (!(thistp specializes sym)) {
      if (sym.isTerm)
        for (val alt <- tp.nonPrivateDecl(sym.name).alternatives)
          if (specializesSym(thistp, sym, thistp, alt))
            tp.decls unlink alt;
      tp.decls enter sym
    }
  }

  /** All types in list must be polytypes with type parameter lists of
   *  same length as tparams.
   *  Returns list of list of bounds infos, where corresponding type
   *  parameters are renamed to tparams.
   */
  private def matchingBounds(tps: List[Type], tparams: List[Symbol]): List[List[Type]] =
    tps map {
      case PolyType(tparams1, _) if (tparams1.length == tparams.length) =>
        tparams1 map (tparam => tparam.info.substSym(tparams1, tparams))
      case _ =>
        throw new NoCommonType(tps)
    }

  /** All types in list must be polytypes with type parameter lists of
   *  same length as tparams.
   *  Returns list of instance types, where corresponding type
   *  parameters are renamed to tparams.
   */
  private def matchingInstTypes(tps: List[Type], tparams: List[Symbol]): List[Type] =
    tps map {
      case PolyType(tparams1, restpe) if (tparams1.length == tparams.length) =>
        restpe.substSym(tparams1, tparams)
      case _ =>
        throw new NoCommonType(tps)
    }

  /** All types in list must be method types with equal parameter types.
   *  Returns list of their result types.
   */
  private def matchingRestypes(tps: List[Type], pts: List[Type]): List[Type] =
    tps map {
      case MethodType(pts1, res) if (isSameTypes(pts1, pts)) =>
        res
      case _ =>
        throw new NoCommonType(tps)
    }

// Errors and Diagnostics ---------------------------------------------------------

  /** An exception signalling a type error */
  class TypeError(val pos: int, val msg: String) extends java.lang.Error(msg) {
    def this(msg: String) = this(Position.NOPOS, msg)
  }

  class NoCommonType(tps: List[Type]) extends java.lang.Error(
    "lub/glb of incompatible types: " + tps.mkString("", " and ", ""));

  /** An exception signalling a malformed type */
  class MalformedType(msg: String) extends TypeError(msg) {
    def this(pre: Type, tp: String) = this("malformed type: " + pre + "#" + tp)
  }

  /** An exception signalling a malformed closure */
  class MalformedClosure(ts: List[Type])
       extends TypeError("no common type instance of base types " +
                         ts.mkString("", " and ", "") + " exists");

  /** An exception signalling a variance annotation/usage conflict */
  class VarianceError(msg: String) extends TypeError(msg);

  /** The current indentation string for traces */
  private var indent: String = "";

  /** Perform operation `p' on arguments `tp1', `arg2' and print trace of computation */
  private def explain[T](op: String, p: (Type, T) => boolean, tp1: Type, arg2: T): boolean = {
    System.out.println(indent + tp1 + " " + op + " " + arg2 + "?");
    indent = indent + "  "
    val result = p(tp1, arg2)
    indent = indent.substring(0, indent.length() - 2)
    System.out.println(indent + result)
    result
  }

  /** If option `explaintypes' is set, print a subtype trace for `found' &lt;: `required' */
  def explainTypes(found: Type, required: Type): unit =
    if (settings.explaintypes.value) {
      val s = explainSwitch
      explainSwitch = true
      found <:< required
      explainSwitch = s
    }
}
