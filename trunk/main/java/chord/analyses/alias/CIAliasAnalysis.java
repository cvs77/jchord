/*
 * Copyright (c) 2008-2009, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 */
package chord.analyses.alias;

import java.util.HashSet;
import java.util.Set;

import joeq.Class.jq_Field;
import joeq.Compiler.Quad.Quad;
import joeq.Compiler.Quad.RegisterFactory.Register;

import chord.bddbddb.Rel.RelView;
import chord.project.Chord;
import chord.project.JavaAnalysis;
import chord.project.ProgramRel;
import chord.project.Project;

import chord.util.SetUtils;

/**
 * Context-insensitive may alias analysis.
 * 
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
@Chord(
	name = "ci-alias-java",
	consumedNames = { "VH", "FH", "HFH" }
)
public class CIAliasAnalysis extends JavaAnalysis {
	private ProgramRel relVH;
	private ProgramRel relFH;
	private ProgramRel relHFH;
	public void run() {
		relVH  = (ProgramRel) Project.getTrgt("VH");
		relFH  = (ProgramRel) Project.getTrgt("FH");
		relHFH = (ProgramRel) Project.getTrgt("HFH");
	}
	/**
	 * Provides the abstract object to which a given local variable
	 * may point.
	 * 
	 * @param	var	A local variable.
	 * 
	 * @return	The abstract object to which the given local variable
	 * 			may point.
	 */
	public Obj pointsTo(Register var) {
		if (!relVH.isOpen())
			relVH.load();
		RelView view = relVH.getView();
		view.selectAndDelete(0, var);
		Iterable<Quad> res = view.getAry1ValTuples();
		Set<Ctxt> pts = SetUtils.newSet(view.size());
		for (Quad inst : res)
			pts.add(new Ctxt(new Quad[] { inst }));
		view.free();
		return new Obj(pts);
	}
	/**
	 * Provides the abstract object to which a given static field
	 * may point.
	 * 
	 * @param	field	A static field.
	 * 
	 * @return	The abstract object to which the given static field
	 * 			may point.
	 */
	public Obj pointsTo(jq_Field field) {
		if (!relFH.isOpen())
			relFH.load();
		RelView view = relFH.getView();
		view.selectAndDelete(0, field);
		Iterable<Quad> res = view.getAry1ValTuples();
		Set<Ctxt> pts = SetUtils.newSet(view.size());
		for (Quad inst : res)
			pts.add(new Ctxt(new Quad[] { inst }));
		view.free();
		return new Obj(pts);
	}
	/**
	 * Provides the abstract object to which a given instance field
	 * of a given abstract object may point.
	 * 
	 * @param	obj		An abstract object.
	 * @param	field	An instance field.
	 * 
	 * @return	The abstract object to which the given instance field
	 * 			of the given abstract object may point.
	 */
	public Obj pointsTo(Obj obj, jq_Field field) {
		if (!relHFH.isOpen())
			relHFH.load();
		Set<Ctxt> pts = new HashSet<Ctxt>();
		for (Ctxt ctxt : obj.pts) {
			Quad site = ctxt.getElems()[0];
			RelView view = relHFH.getView();
			view.selectAndDelete(0, site);
			view.selectAndDelete(1, field);
			Iterable<Quad> res = view.getAry1ValTuples();
			for (Quad inst : res)
				pts.add(new Ctxt(new Quad[] { inst }));
			view.free();
		}
		return new Obj(pts);
	}
	/**
	 * Frees relations used by this program analysis if they are in
	 * memory.
	 * <p>
	 * This method must be called after clients are done exercising
	 * the interface of this analysis.
	 */
	public void free() {
		if (relVH.isOpen())
			relVH.close();
		if (relFH.isOpen())
			relFH.close();
		if (relHFH.isOpen())
			relHFH.close();
	}
}
