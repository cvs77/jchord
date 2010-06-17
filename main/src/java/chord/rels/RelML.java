/*
 * Copyright (c) 2008-2010, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 */
package chord.rels;

import joeq.Class.jq_Method;
import joeq.Compiler.Quad.Inst;
import chord.doms.DomL;
import chord.doms.DomM;
import chord.program.Program;
import chord.project.Chord;
import chord.project.analyses.ProgramRel;

/**
 * Relation containing each tuple (m,l) such that method m contains
 * synchronized statement l.
 *
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
@Chord(
	name = "ML",
	sign = "M0,L0:M0_L0"
)
public class RelML extends ProgramRel {
	public void fill() {
		DomM domM = (DomM) doms[0];
		DomL domL = (DomL) doms[1];
		int numL = domL.size();
		Program program = Program.getProgram();
		for (int lIdx = 0; lIdx < numL; lIdx++) {
			Inst i = domL.get(lIdx);
			jq_Method m = program.getMethod(i);
			int mIdx = domM.indexOf(m);
			add(mIdx, lIdx);
		}
	}
}
