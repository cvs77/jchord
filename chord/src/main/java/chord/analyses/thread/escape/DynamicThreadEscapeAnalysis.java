/*
 * Copyright (c) 2008-2009, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 */
package chord.analyses.thread.escape;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

import chord.project.ChordRuntimeException;
import chord.project.Properties;
import chord.util.IndexMap;
import chord.instr.InstrScheme;
import chord.project.Chord;
import chord.project.DynamicAnalysis;
import chord.project.ProgramRel;
import chord.project.Project;

import gnu.trove.TIntHashSet;
import gnu.trove.TIntIntHashMap;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntArrayList;

// todo: specify output rels in @Chord annotation
// and enable user to enable isFlowSen/isFlowIns

/**
 * 
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
@Chord(
	name = "dynamic-thresc-java",
	namesOfSigns = { "visitedE", "flowInsEscE", "flowSenEscE" },
	signs = { "E0", "E0", "E0" }
)
public class DynamicThreadEscapeAnalysis extends DynamicAnalysis {
    // set of all currently escaping objects
    private TIntHashSet escObjs;
	// map from each object to a list containing each non-null-valued
	// instance field of reference type along with that value
	private TIntObjectHashMap<List<FldObj>> objToFldObjs;
    // map from each object to the index in domain H of its alloc site
    private TIntIntHashMap objToHidx; 
    // map from the index in domain H of each alloc site not yet known
	// to be flow-ins. thread-escaping to the list of indices in
	// domain E of instance field/array deref sites that should become
    // flow-ins. thread-escaping if this alloc site becomes flow-ins.
	// thread-escaping
    // invariant: isHidxEsc[h] = true => HidxToPendingEidxs[h] == null
    private TIntArrayList[] HidxToPendingEidxs;
    // isHidxEsc[h] == true iff alloc site having index h in domain H
	// is flow-ins. thread-escaping
	private boolean[] isHidxEsc;
    // isEidxFlowSenEsc[e] == true iff instance field/array deref
	// site having index e in domain E is flow-sen. thread-escaping
	private boolean[] isEidxFlowSenEsc;
    // isEidxFlowInsEsc[e] == true iff instance field/array deref
	// site having index e in domain E is flow-ins. thread-escaping
	private boolean[] isEidxFlowInsEsc;
	// isEidxVisited[e] == true iff instance field/array deref site
	// having index e in domain E is visited during the execution
	private boolean[] isEidxVisited;

	private boolean isFlowIns = true;
	private boolean isFlowSen = true;

	protected ProgramRel relVisitedE;
	protected ProgramRel relFlowInsEscE;
	protected ProgramRel relFlowSenEscE;

	private int numE;
	private int numH;
	private boolean convert;

    protected InstrScheme instrScheme;
    public InstrScheme getInstrScheme() {
    	if (instrScheme != null)
    		return instrScheme;
    	instrScheme = new InstrScheme();
    	boolean convert = System.getProperty(
    		"chord.convert", "false").equals("true");
    	if (convert)
    		instrScheme.setConvert();
    	instrScheme.setNewAndNewArrayEvent(true, false, true);
    	instrScheme.setPutstaticReferenceEvent(false, false, false, true);
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

	public void initAllPasses() {
		escObjs = new TIntHashSet();
		objToFldObjs = new TIntObjectHashMap<List<FldObj>>();
		numE = instrumentor.getEmap().size();
		isEidxVisited = new boolean[numE];
		if (convert)
			relVisitedE = (ProgramRel) Project.getTrgt("visitedE");
		if (isFlowIns) {
			isEidxFlowInsEsc = new boolean[numE];
			if (convert) {
				relFlowInsEscE =
					(ProgramRel) Project.getTrgt("flowInsEscE");
			}
			numH = instrumentor.getHmap().size();
			HidxToPendingEidxs = new TIntArrayList[numH];
			isHidxEsc = new boolean[numH];
			objToHidx = new TIntIntHashMap();
		}
		if (isFlowSen) {
			isEidxFlowSenEsc = new boolean[numE];
			if (convert) {
 				relFlowSenEscE =
					(ProgramRel) Project.getTrgt("flowSenEscE");
			}
		}
	}

	public void initPass() {
		escObjs.clear();
		objToFldObjs.clear();
		if (isFlowSen) {
			for (int i = 0; i < numH; i++)
				HidxToPendingEidxs[i] = null;
			for (int i = 0; i < numH; i++)
				isHidxEsc[i] = false;
			objToHidx.clear();
		}
	}

	public void donePass() {
		System.out.println("***** STATS *****");
		int numVisited = 0;
		int numFlowInsOriginalEsc = 0;
		int numFlowInsAdjustedEsc = 0;
		int numFlowSenEsc = 0;
		int numAllocEsc = 0;
		for (int i = 0; i < numE; i++) {
			if (isEidxVisited[i])
				numVisited++;
		}
		if (isFlowSen) {
			for (int i = 0; i < numE; i++) {
				if (isEidxFlowSenEsc[i]) {
					numFlowSenEsc++;
				}
			}
		}
		if (isFlowIns) {
			for (int i = 0; i < numE; i++) {
				if (isEidxFlowInsEsc[i])
					numFlowInsOriginalEsc++;
				else if (isFlowSen && isEidxFlowSenEsc[i])
					numFlowInsAdjustedEsc++;
			}
			numFlowInsAdjustedEsc += numFlowInsOriginalEsc;
			for (int i = 0; i < numH; i++) {
				if (isHidxEsc[i])
					numAllocEsc++;
			}
		}
		System.out.println("numVisited: " + numVisited +
			" numFlowSenEsc: " + numFlowSenEsc +
			" numFlowInsEsc (original): " + numFlowInsOriginalEsc +
			" numFlowInsEsc (adjusted): " + numFlowInsAdjustedEsc +
			" numAllocEsc: " + numAllocEsc);
	}

	public void doneAllPasses() {
		if (convert) {
			relVisitedE.zero();
			for (int i = 0; i < numE; i++) {
				if (isEidxVisited[i])
					relVisitedE.add(i);
			}
			relVisitedE.save();
			if (isFlowIns) {
				relFlowInsEscE.zero();
				for (int i = 0; i < numE; i++) {
					if (isEidxFlowInsEsc[i])
						relFlowInsEscE.add(i);
				}
				relFlowInsEscE.save();
			}
			if (isFlowSen) {
				relFlowSenEscE.zero();
				for (int i = 0; i < numE; i++) {
					if (isEidxFlowSenEsc[i])
						relFlowSenEscE.add(i);
				}
				relFlowSenEscE.save();
			}
		}

		IndexMap<String> Emap = instrumentor.getEmap();
		String outDirName = Properties.outDirName;
		try {
			PrintWriter writer = new PrintWriter(new FileWriter(
				new File(outDirName, "visited.txt")));
			for (int i = 0; i < numE; i++) {
				if (isEidxVisited[i])
					writer.println(Emap.get(i));
			}
			writer.close();
			if (isFlowIns) {
				writer = new PrintWriter(new FileWriter(
					new File(outDirName, "flowInsEsc.txt")));
				for (int i = 0; i < numE; i++) {
					if (isEidxFlowInsEsc[i])
						writer.println(Emap.get(i));
				}
				writer.close();
			}
			if (isFlowSen) {
				writer = new PrintWriter(new FileWriter(
					new File(outDirName, "flowSenEsc.txt")));
				for (int i = 0; i < numE; i++) {
					if (isEidxFlowSenEsc[i])
						writer.println(Emap.get(i));
				}
				writer.close();
			}
		} catch (IOException ex) {
			throw new ChordRuntimeException(ex);
		}
	}

	public void processNewOrNewArray(int h, int t, int o) {
		if (o != 0) {
			objToFldObjs.remove(o);
			escObjs.remove(o);
			if (isFlowIns) {
				objToHidx.remove(o);
				if (h >= 0)
					objToHidx.put(o, h);
			}
		}
	}
	public void processGetfieldReference(int e, int t, int b, int f, int o) { 
		processHeapRd(e, b);
	}
	public void processPutfieldReference(int e, int t, int b, int f, int o) {
		processHeapWr(e, b, f, o);
	}
	public void processAloadReference(int e, int t, int b, int i, int o) { 
		processHeapRd(e, b);
	}
	public void processAstoreReference(int e, int t, int b, int i, int o) {
		processHeapWr(e, b, i, o);
	}

	public void processGetfieldPrimitive(int e, int t, int b, int f) { 
		processHeapRd(e, b);
	}
	public void processPutfieldPrimitive(int e, int t, int b, int f) {
		processHeapRd(e, b);
	}
	public void processAloadPrimitive(int e, int t, int b, int i) { 
		processHeapRd(e, b);
	}
	public void processAstorePrimitive(int e, int t, int b, int i) {
		processHeapRd(e, b);
	}

	public void processPutstaticReference(int e, int t, int f, int o) { 
		if (o != 0) {
			markAndPropEsc(o);
		}
	}
	public void processThreadStart(int p, int t, int o) { 
		if (o != 0) {
			markAndPropEsc(o);
		}
	}
	private void processHeapRd(int eIdx, int b) {
		if (eIdx >= 0 && b != 0) {
			isEidxVisited[eIdx] = true;
			if (isFlowSen) {
				if (!isEidxFlowSenEsc[eIdx] && escObjs.contains(b)) {
					isEidxFlowSenEsc[eIdx] = true;
				}
			}
			if (isFlowIns) {
				if (!isEidxFlowInsEsc[eIdx]) {
					if (objToHidx.containsKey(b)) {
						int hIdx = objToHidx.get(b);
						if (isHidxEsc[hIdx]) {
							isEidxFlowInsEsc[eIdx] = true;
						} else {
							TIntArrayList l = HidxToPendingEidxs[hIdx];
							if (l == null) {
								l = new TIntArrayList();
								HidxToPendingEidxs[hIdx] = l;
								l.add(eIdx);
							} else if (!l.contains(eIdx)) {
								l.add(eIdx);
							}
						}
					}
				}
			}
		}
	}
	private void processHeapWr(int eIdx, int b, int fIdx, int r) {
		processHeapRd(eIdx, b);
		if (b != 0 && fIdx >= 0) {
			if (r == 0) {
				// remove field fIdx if it is there
				List<FldObj> l = objToFldObjs.get(b);
				if (l != null) {
					int n = l.size();
					for (int i = 0; i < n; i++) {
						FldObj fo = (FldObj) l.get(i);
						if (fo.f == fIdx) {
							l.remove(i);
							return;
						}
					}
				}
			} else {
				List<FldObj> l = objToFldObjs.get(b);
				if (l == null) {
					l = new ArrayList<FldObj>();
					objToFldObjs.put(b, l);
				} else {
					int n = l.size();
					for (int i = 0; i < n; i++) {
						FldObj fo = (FldObj) l.get(i);
						if (fo.f == fIdx) {
							fo.o = r;
							return;
						}
					}
				}
				l.add(new FldObj(fIdx, r));
				if (escObjs.contains(b))
					markAndPropEsc(r);
			}
		}
	}
	private void markHesc(int hIdx) {
		if (!isHidxEsc[hIdx]) {
			isHidxEsc[hIdx] = true;
			TIntArrayList l = HidxToPendingEidxs[hIdx];
			if (l != null) {
				int n = l.size();
				for (int i = 0; i < n; i++) {
					int eIdx = l.get(i);
					isEidxFlowInsEsc[eIdx] = true;
				}
				HidxToPendingEidxs[hIdx] = null;
			}
		}
	}
    private void markAndPropEsc(int o) {
        if (escObjs.add(o)) {
        	if (isFlowSen) {
				if (objToHidx.containsKey(o)) {
					int hIdx = objToHidx.get(o);
					markHesc(hIdx);
				}
        	}
			List<FldObj> l = objToFldObjs.get(o);
			if (l != null) {
				int n = l.size();
				for (int i = 0; i < n; i++) {
					FldObj fo = (FldObj) l.get(i);
					markAndPropEsc(fo.o);
				}
			}
		}
	}
}

class FldObj {
    public int f;
    public int o;
    public FldObj(int f, int o) { this.f = f; this.o = o; }
}
