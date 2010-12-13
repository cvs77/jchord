/*
 * Copyright (c) 2008-2010, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 * Licensed under the terms of the New BSD License.
 */
package chord.analyses.escape.shape.path;

import gnu.trove.TIntArrayList;
import gnu.trove.TIntHashSet;
import gnu.trove.TIntIntHashMap;
import gnu.trove.TIntObjectHashMap;

import java.io.PrintWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import joeq.Compiler.Quad.Quad;
import joeq.Compiler.Quad.Operator;
import joeq.Compiler.Quad.Operator.Getstatic;
import joeq.Compiler.Quad.Operator.Putstatic;
import joeq.Compiler.Quad.Operator.Putfield;
import joeq.Compiler.Quad.Operator.Getfield;
import joeq.Compiler.Quad.Operator.ALoad;
import joeq.Compiler.Quad.Operator.AStore;

import chord.analyses.escape.ThrEscException;
import chord.analyses.heapacc.DomE;
import chord.analyses.alloc.DomH;
import chord.analyses.field.DomF;
import chord.instr.InstrScheme;
import chord.program.Program;
import chord.program.MethodElem;
import chord.project.Chord;
import chord.project.Config;
import chord.project.Messages;
import chord.project.OutDirUtils;
import chord.project.ClassicProject;
import chord.project.analyses.DynamicAnalysis;
import chord.project.analyses.ProgramRel;
import chord.util.IntArraySet;
import chord.util.FileUtils;

/**
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
@Chord(
	name = "thresc-shape-path-java",
	produces = { "accE", "escE", "likelyLocE", "locEH" }, 
	consumes = { "checkExcludedE" },
	namesOfSigns = { "accE", "escE", "likelyLocE", "locEH" },
	signs = { "E0", "E0", "E0", "E0,H0:E0_H0" }
)
public class ThreadEscapePathAnalysis extends DynamicAnalysis {
	private static final boolean verbose = false;

	private static final String printAccFileName = System.getProperty("chord.escape.check.file");

	private static final int TC = 0, TC_ALLOC = 1, TC_ALLOC_PRUNE = 2;
	private static final int checkKind;
	static {
		String s = System.getProperty("chord.escape.check.kind", "tc");
		if (s.equals("tc"))
			checkKind = TC;
		else if (s.equals("tc_alloc"))
			checkKind = TC_ALLOC;
		else if (s.equals("tc_alloc_prune"))
			checkKind = TC_ALLOC_PRUNE;
		else {
			checkKind = -1;
			Messages.fatal("Invalid value for chord.escape.check.kind");
		}
	}

	private static final boolean doStrongUpdates = true;

 	// use field 0 in domF instead of array indices in OtoFOlistFwd
	private static final boolean smashArrayElems = false;

	private int numPrintedHeaps = 0;

 	// contains only non-null objects
	private final IntArraySet tmpO = new IntArraySet();
	private final IntArraySet tmpH = new IntArraySet();

	// accesses to be checked; allows excluding certain accesses (e.g.
	// from JDK library code)
	private boolean[] chkE;

	// visited accesses (subset of chkE)
	private boolean[] accE;

	// accesses to be ignored because something went bad while computing
	// set of "relevant" allocation sites for it (subset of accE)
	private boolean[] badE;

	// provably thread-escaping accesses (subset of accE)
	private boolean[] escE;

	// accesses not observed to thread-escape but provably impossible for
	// static analysis to prove thread-local (subset of accE)
	private boolean[] impE;

	// visited allocation sites
	private boolean[] accH;

	// map from each access deemed thread-local to set of allocation sites
	// deemed "relevant" to proving it
	private IntArraySet[] EtoLocHset;

	// set of escaping objects; once an object is put into this set,
	// it remains in it for the rest of the execution
	private TIntHashSet escO;

	// map from each object to a list of each instance field of ref type
	// along with the pointed object; fields with null value are not stored
 	// invariant: OtoFOlistFwd(o) contains (f,o1) and (f,o2) => o1=o2
	private TIntObjectHashMap<List<FldObj>> OtoFOlistFwd;

	// inverse of above map
	private TIntObjectHashMap<List<FldObj>> OtoFOlistInv;

	// map from each object to the index in domH of its alloc site
    private TIntIntHashMap OtoH;

	private int timestamp = 1;	// must start at 1

	// map from each object to its timestamp
	// records the order in which objects are created; note that the
	// object IDs are not in order of creation since an object can be
	// assigned an ID only after its constructor has executed
	private TIntIntHashMap OtoS;

	private TIntIntHashMap OtoT;

	// map from each allocation site to list of all objects allocated
	// at that site
	private TIntArrayList[] HtoOlist;
	
    private InstrScheme instrScheme;

	private DomH domH;
	private DomE domE;
	private DomF domF;
	private int numH, numE, numF;

	@Override
    public InstrScheme getInstrScheme() {
    	if (instrScheme != null) return instrScheme;
    	instrScheme = new InstrScheme();
    	instrScheme.setNewAndNewArrayEvent(true, true, true);
    	instrScheme.setPutstaticReferenceEvent(false, false, false, false, true);
    	instrScheme.setThreadStartEvent(false, false, true);
    	instrScheme.setGetfieldPrimitiveEvent(true, false, true, false);
    	instrScheme.setPutfieldPrimitiveEvent(true, false, true, false);
    	instrScheme.setAloadPrimitiveEvent(true, false, true, false);
    	instrScheme.setAstorePrimitiveEvent(true, false, true, false);
    	instrScheme.setGetfieldReferenceEvent(true, false, true, false, false);
    	instrScheme.setPutfieldReferenceEvent(true, false, true, true, true);
    	instrScheme.setAloadReferenceEvent(true, false, true, false, false);
    	instrScheme.setAstoreReferenceEvent(true, false, true, true, true);
    	return instrScheme;
    }

	@Override
	public void initAllPasses() {
		if (smashArrayElems)
			assert (!doStrongUpdates);
		escO = new TIntHashSet();
		OtoFOlistFwd = new TIntObjectHashMap<List<FldObj>>();
		OtoFOlistInv = new TIntObjectHashMap<List<FldObj>>();
		OtoH = new TIntIntHashMap();

		domE = (DomE) ClassicProject.g().getTrgt("E");
		ClassicProject.g().runTask(domE);
		numE = domE.size();
		domH = (DomH) ClassicProject.g().getTrgt("H");
		ClassicProject.g().runTask(domH);
		numH = domH.size();
		domF = (DomF) ClassicProject.g().getTrgt("F");
		ClassicProject.g().runTask(domF);
		numF = domF.size();

		chkE = new boolean[numE];
		if (printAccFileName != null) {
			String outDirName = Config.outDirName;
			Program program = Program.g();
			Class[] classes = new Class[] { ALoad.class, AStore.class, Getfield.class, Putfield.class };
			File file = new File(outDirName, printAccFileName);
			List<String> list = new ArrayList<String>();
			FileUtils.readFileToList(file, list);
			for (String s : list) {
				String[] a = s.split(" ");
				MethodElem me = MethodElem.parse(a[0]);
				Quad q = program.getQuad(me, classes);
				int e = domE.indexOf(q);
				assert (e != -1) : ("Quad not found in domain E: " + q);
				System.out.println("e: " + e);
				chkE[e] = true;
			}
		} else {
			ProgramRel relCheckExcludedE = (ProgramRel) ClassicProject.g().getTrgt("checkExcludedE");
			relCheckExcludedE.load();
			for (int e = 0; e < numE; e++) {
				Quad q = domE.get(e);
				Operator op = q.getOperator();
				if (op instanceof Getstatic || op instanceof Putstatic)
					continue;
				if (!relCheckExcludedE.contains(e))
					chkE[e] = true;
			}
			relCheckExcludedE.close();
		}

		accE = new boolean[numE];
		badE = new boolean[numE];
		escE = new boolean[numE];
		impE = new boolean[numE];
		EtoLocHset = new IntArraySet[numE];
		accH = new boolean[numH];
		OtoT = new TIntIntHashMap();

		if (checkKind == TC_ALLOC || checkKind == TC_ALLOC_PRUNE) {
			OtoS = new TIntIntHashMap();
			HtoOlist = new TIntArrayList[numH];
		}
	}

	@Override
	public void doneAllPasses() {
        int numAccH = 0;
        for (int h = 0; h < numH; h++) {
            if (accH[h]) numAccH++;
        }
        System.out.println("numAccH: " + numAccH);

		ProgramRel  accErel  = (ProgramRel) ClassicProject.g().getTrgt("accE");
		ProgramRel  escErel  = (ProgramRel) ClassicProject.g().getTrgt("escE");
		ProgramRel  locErel  = (ProgramRel) ClassicProject.g().getTrgt("likelyLocE");
		ProgramRel  locEHrel = (ProgramRel) ClassicProject.g().getTrgt("locEH");
		PrintWriter accEout  = OutDirUtils.newPrintWriter("shape_pathAccE.txt");
		PrintWriter badEout  = OutDirUtils.newPrintWriter("shape_pathBadE.txt");
		PrintWriter escEout  = OutDirUtils.newPrintWriter("shape_pathEscE.txt");
		PrintWriter impEout  = OutDirUtils.newPrintWriter("shape_pathImpE.txt");
		PrintWriter locEHout = OutDirUtils.newPrintWriter("shape_pathLocEH.txt");
		accErel.zero();
		escErel.zero();
		locErel.zero();
		locEHrel.zero();
		for (int e = 0; e < numE; e++) {
			if (accE[e]) {
				accErel.add(e);
				String estr = domE.get(e).toVerboseStr();
				accEout.println(estr);
				if (badE[e]) {
					badEout.println(estr);
				} else if (escE[e]) {
					escErel.add(e);
					escEout.println(estr);
				} else if (impE[e]) {
					escErel.add(e);
					impEout.println(estr);
				} else {
					locErel.add(e);
					locEHout.println(estr);
					IntArraySet hs = EtoLocHset[e];
					if (hs != null) {
						int n = hs.size();
						for (int i = 0; i < n; i++) {
							int h = hs.get(i);
							locEHrel.add(e, h);
							String hstr = ((Quad) domH.get(h)).toVerboseStr();
							locEHout.println("#" + hstr);
						}
					}
				}
			}
		}
		accEout.close();
		badEout.close();
		escEout.close();
		impEout.close();
		locEHout.close();
		accErel.save();
		escErel.save();
		locErel.save();
		locEHrel.save();
	}

	private String eStr(int e) {
		return e < 0 ? Integer.toString(e) : domE.get(e).toJavaLocStr();
	}

	private String hStr(int h) {
		if (h <= 0)
			return Integer.toString(h);
		Quad q = (Quad) domH.get(h);
		return DomH.getType(q) + " " + q.toJavaLocStr(); 
	}

	private String fStr(int f) {
		if (f <= 0) return Integer.toString(f);
		if (f < numF) return domF.get(f).getName().toString();
		return Integer.toString(f - numF);
	}

	@Override
	public void processNewOrNewArray(int h, int t, int o) {
		if (verbose) System.out.println(t + " NEW " + hStr(h) + " o=" + o);
		if (o == 0 || t == 0) return;
		assert (!escO.contains(o));
		assert (!OtoFOlistFwd.containsKey(o));
		assert (!OtoFOlistInv.containsKey(o));
		int result = OtoT.put(o, t);
		assert (result == 0);
		if (h >= 0) {
			accH[h] = true;
			result = OtoH.put(o, h);
			assert (result == 0);
		}
		if (checkKind == TC_ALLOC || checkKind == TC_ALLOC_PRUNE) {
			result = OtoS.put(o, timestamp++);
			assert (result == 0);
			if (h >= 0) {
				TIntArrayList ol = HtoOlist[h];
				if (ol == null) {
					ol = new TIntArrayList();
					HtoOlist[h] = ol;
				}
				ol.add(o);
			}
		}
	}

	@Override
	public void processGetfieldPrimitive(int e, int t, int b, int f) { 
		if (verbose) System.out.println(t + " GETFLD_PRI " + eStr(e) + " b=" + b);
		if (e >= 0) processHeapRd(e, b);
	}

	@Override
	public void processAloadPrimitive(int e, int t, int b, int i) { 
		if (verbose) System.out.println(t + " ALOAD_PRI " + eStr(e));
		if (e >= 0) processHeapRd(e, b);
	}

	@Override
	public void processGetfieldReference(int e, int t, int b, int f, int o) { 
		if (verbose) System.out.println(t + " GETFLD_REF " + eStr(e) + " b=" + b);
		if (e >= 0) processHeapRd(e, b);
	}

	@Override
	public void processAloadReference(int e, int t, int b, int i, int o) { 
		if (verbose) System.out.println(t + " ALOAD_REF " + eStr(e));
		if (e >= 0)
			processHeapRd(e, b);
	}

	@Override
	public void processPutfieldPrimitive(int e, int t, int b, int f) {
		if (verbose) System.out.println(t + " PUTFLD_PRI " + eStr(e) + " b=" + b);
		if (e >= 0) processHeapRd(e, b);
	}

	@Override
	public void processAstorePrimitive(int e, int t, int b, int i) {
		if (verbose) System.out.println(t + " ASTORE_PRI " + eStr(e) + " b=" + b);
		if (e >= 0) processHeapRd(e, b);
	}

	@Override
	public void processPutfieldReference(int e, int t, int b, int f, int o) {
		if (verbose) System.out.println(t + " PUTFLD_REF " + eStr(e) + " b=" + b);
		if (e >= 0) processHeapWr(e, b, f, o);
	}

	@Override
	public void processAstoreReference(int e, int t, int b, int i, int o) {
		if (verbose) System.out.println(t + " ASTORE_REF " + eStr(e) + " b=" + b);
		if (e >= 0) processHeapWr(e, b, smashArrayElems ? 0 : i + numF, o);
	}

	@Override
	public void processPutstaticReference(int e, int t, int b, int f, int o) { 
		if (verbose) System.out.println(t + " PUTSTATIC_REF " + eStr(e));
		if (o != 0)
			markAndPropEsc(o);
	}

	@Override
	public void processThreadStart(int p, int t, int o) { 
		if (verbose) System.out.println(t + " START " + p);
		if (o != 0) markAndPropEsc(o);
	}

	// assumes 'b' != 0 and 'tmpO' and 'tmpH' are not needed
	private boolean computeTC(int b) throws ThrEscException {
		tmpO.clear();
		tmpH.clear();
		int h = OtoH.get(b);
		if (h == 0)
			throw new ThrEscException();
		int t = OtoT.get(b);
		if (t == 0)
			throw new ThrEscException();
		tmpO.add(b);
		tmpH.add(h);
		// Note: tmpO.size() can change in body of below loop!
		for (int i = 0; i < tmpO.size(); i++) {
			int o = tmpO.get(i);
			List<FldObj> foList = OtoFOlistInv.get(o);
			if (foList != null) {
				int n = foList.size();
				for (int j = 0; j < n; j++) {
					FldObj fo = foList.get(j);
					int o2 = fo.o;
					if (tmpO.add(o2)) {
						int h2 = OtoH.get(o2);
						if (h2 == 0)
							throw new ThrEscException();
						tmpH.add(h2);
						int t2 = OtoT.get(o2);
						if (t2 == 0)
							throw new ThrEscException();
						assert (t2 == t);
					}
				}
			}
		}
		boolean imp;
		if (checkKind == TC_ALLOC_PRUNE)
			imp = computeExtendedTC(t);
		else {
			imp = false;
			if (checkKind == TC_ALLOC)
				computeExtendedTC(t);
		}
		return imp;
	}

	// assumes tmpO and tmpH is non-null and contains at least one object
	// potentially adds more to each of them
	private boolean computeExtendedTC(int t) throws ThrEscException {
		int min = tmpO.get(0);
		int n = tmpO.size();
		for (int i = 1; i < n; i++) {
			int o = tmpO.get(i);
			int s = OtoS.get(o);
			if (s == 0)
				throw new ThrEscException();
			if (s < min)
				min = s;
		}
		// Note: tmpO.size() can change in body of below loop!
		for (int i = 0; i < tmpO.size(); i++) {
			int o = tmpO.get(i);
			int h = OtoH.get(o);  // h guaranteed to be > 0
			TIntArrayList l = HtoOlist[h];
			assert (l != null);
			int m = l.size();
			for (int j = 0; j < m; j++) {
				int o2 = l.get(j);
				if (o2 == o)
					continue;
				int t2 = OtoT.get(o2);
				if (t2 == 0)
					throw new ThrEscException();
				if (t2 != t)
					continue;
				int s2 = OtoS.get(o2);
				if (s2 == 0)
					throw new ThrEscException();
				if (s2 < min)
					continue;
				if (escO.contains(o2)) {
					if (checkKind == TC_ALLOC_PRUNE) {
						tmpO.add(o2); // XXX
						return true;
					}
					continue;
				}
				if (tmpO.add(o2)) {
					int h2 = OtoH.get(o2);
					if (h2 == 0)
						throw new ThrEscException();
					tmpH.add(h2);
				}
				List<FldObj> foList = OtoFOlistInv.get(o2);
				if (foList == null)
					continue;
				int p = foList.size();
				for (int k = 0; k < p; k++) {
					FldObj fo = foList.get(k);
					int o3 = fo.o;
					int t3 = OtoT.get(o3);
					if (t3 == 0)
						throw new ThrEscException();
					assert (t3 == t);
					int s3 = OtoS.get(o3);
					if (s3 == 0)
						throw new ThrEscException();
					if (s3 < min)
						continue;
					if (escO.contains(o3)) {
						if (checkKind == TC_ALLOC_PRUNE) {
							tmpO.add(o3);  // XXX
							return true;
						}
						continue;
					}
					if (tmpO.add(o3)) {
						int h3 = OtoH.get(o3);
						if (h3 == 0)
							throw new ThrEscException();
						tmpH.add(h3);
					}
				}
			}
		}
		return false;
	}

	private void processHeapRd(int e, int b) {
		if (!chkE[e] || escE[e] || badE[e])
			return;
		accE[e] = true;
		if (b == 0) return;
		if (escO.contains(b)) {
			escE[e] = true;
			return;
		}
		boolean imp;
		try {
			imp = computeTC(b);
			if (printAccFileName != null)
				printHeap(e);
		} catch (ThrEscException ex) {
			badE[e] = true;
			return;
		}
		if (imp) {
			impE[e] = true;
		} else {
			IntArraySet hs = EtoLocHset[e];
			if (hs == null) {
				hs = new IntArraySet();
				EtoLocHset[e] = hs;
			}
			int n = tmpH.size();
			for (int i = 0; i < n; i++) {
				int h = tmpH.get(i);
				hs.add(h);
			}
		}
	}

	private void removeInv(int rOld, int f, int b) {
		List<FldObj> inv = OtoFOlistInv.get(rOld);
		assert (inv != null);
		int n = inv.size();
		for (int i = 0; i < n; i++) {
			FldObj fo = inv.get(i);
			if (fo.f == f && fo.o == b) {
				inv.remove(i);
				return;
			}
		}
		assert (false);
	}

	private void processHeapWr(int e, int b, int f, int r) {
		processHeapRd(e, b);
		if (b == 0 || f < 0) return;
		if (r == 0) {
			if (!doStrongUpdates)
				return;
			// this is a strong update; so remove field f if it is there
			List<FldObj> fwd = OtoFOlistFwd.get(b);
			if (fwd == null)
				return;
			int rOld = -1;
			int n = fwd.size();
			for (int i = 0; i < n; i++) {
				FldObj fo = fwd.get(i);
				if (fo.f == f) {
					removeInv(fo.o, f, b);
					fwd.remove(i);
					break;
				}
			}
			return;
		}
		List<FldObj> fwd = OtoFOlistFwd.get(b);
		boolean added = false;
		if (fwd == null) {
			fwd = new ArrayList<FldObj>();
			OtoFOlistFwd.put(b, fwd);
		} else if (doStrongUpdates) {
			int n = fwd.size();
			for (int i = 0; i < n; i++) {
				FldObj fo = fwd.get(i);
				if (fo.f == f) {
					removeInv(fo.o, f, b);
					fo.o = r;
					added = true;
					break;
				}
			}
		} else {
			// do not add to fwd if already there;
			// since fwd is a list as opposed to a set, this
			// check must be done explicitly
			int n = fwd.size();
			for (int i = 0; i < n; i++) {
				FldObj fo = fwd.get(i);
				if (fo.f == f && fo.o == r) {
					added = true;
					break;
				}
			}
		}
		if (!added)
			fwd.add(new FldObj(f, r));
		List<FldObj> inv = OtoFOlistInv.get(r);
		if (inv == null) {
			inv = new ArrayList<FldObj>();
			OtoFOlistInv.put(r, inv);
		}
		boolean found = false;
		int n = inv.size();
		for (int i = 0; i < n; i++) {
			FldObj fo = inv.get(i);
			if (fo.f == f && fo.o == b) {
				found = true;
				break;
			}
		}
		if (!found)
			inv.add(new FldObj(f, b));
		if (escO.contains(b))
			markAndPropEsc(r);
	}

    private void markAndPropEsc(int o) {
		if (escO.add(o)) {
			List<FldObj> l = OtoFOlistFwd.get(o);
			if (l != null) {
				for (FldObj fo : l)
					markAndPropEsc(fo.o);
			}
		}
	}

	// prints sub-heap from which object b is reachable
	// destroys tmpO and tmpH
	private void printHeap(int e, int b) {
		PrintWriter out = OutDirUtils.newPrintWriter("heap_" + e + "_" + numPrintedHeaps + ".dot");
		numPrintedHeaps++;
        out.println("digraph G {");
		tmpO.clear();
		tmpH.clear();
		tmpO.add(b);
		for (int i = 0; i < tmpO.size(); i++) {
			int o = tmpO.get(i);
			int h = OtoH.get(o);
			tmpH.add(h);
			int t = OtoS.get(o);
			if (i == 0)
				out.println(o + "[label=\"" + t + "\",style=filled,color=blue];");
			else if (escO.contains(o))
				out.println(o + "[label=\"" + t + "\",style=filled,color=red];");
			else
				out.println(o + "[label=\"" + t + "\",style=filled,color=green];");
			if (h != 0) {
				TIntArrayList l = HtoOlist[h];
				int m = l.size();
				for (int j = 0; j < m; j++) {
					int o2 = l.get(j);
					if (o2 != o)
						tmpO.add(o2);
				}
			}
			List<FldObj> foList = OtoFOlistInv.get(o);
			if (foList != null) {
				int m = foList.size();
				for (int j = 0; j < m; j++) {
					FldObj fo = foList.get(j);
					int o2 = fo.o;
                	out.println(o2 + "->" + o + "[label= \"" + fStr(fo.f) + "\"];");
					tmpO.add(o2);
				}
            }
        }
		int nh = tmpH.size();
		int no = tmpO.size();
		for (int i = 0; i < nh; i++) {
			int h = tmpH.get(i);
			out.print("subgraph cluster_" + h + " { rank=same;style=filled;color=lightgrey;label=\"" + hStr(h) + "\"; ");
			for (int j = 0; j < no; j++) {
				int o = tmpO.get(j);
				if (OtoH.get(o) == h)
					out.print(o + "; ");
			}
			out.println("}");
		}
        out.println("}");
		out.close();
	}

	// prints sub-heap stored in tmpO
	private void printHeap(int e) {
		PrintWriter out = OutDirUtils.newPrintWriter("heap_" + e + "_" + numPrintedHeaps + ".dot");
		numPrintedHeaps++;
        out.println("digraph G {");
		int nh = tmpH.size();
		int no = tmpO.size();
		for (int i = 0; i < no; i++) {
			int o = tmpO.get(i);
			int s = OtoS.get(o);
			int t = OtoT.get(o);
			if (i == 0)
				out.println(o + "[label=\"" + s + "," + t + "\",style=filled,color=blue];");
			else if (escO.contains(o))
				out.println(o + "[label=\"" + s + "," + t + "\",style=filled,color=red];");
			else
				out.println(o + "[label=\"" + s + "," + t + "\",style=filled,color=green];");
			List<FldObj> foList = OtoFOlistInv.get(o);
			if (foList != null) {
				int m = foList.size();
				for (int j = 0; j < m; j++) {
					FldObj fo = foList.get(j);
					int o2 = fo.o;
					if (tmpO.contains(o2))
                		out.println(o2 + "->" + o + "[label= \"" + fStr(fo.f) + "\"];");
				}
            }
        }
		for (int i = 0; i < nh; i++) {
			int h = tmpH.get(i);
			out.print("subgraph cluster_" + h + " { rank=same;style=filled;color=lightgrey;label=\"" + hStr(h) + "\"; ");
			for (int j = 0; j < no; j++) {
				int o = tmpO.get(j);
				if (OtoH.get(o) == h)
					out.print(o + "; ");
			}
			out.println("}");
		}
        out.println("}");
		out.close();
	}
}
