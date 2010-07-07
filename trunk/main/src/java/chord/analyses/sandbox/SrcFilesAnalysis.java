/*
 * Copyright (c) 2008-2010, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 */
package chord.analyses.sandbox;

import java.util.Set;
import java.util.HashSet;
import java.util.List;

import joeq.Class.jq_Class;
import joeq.Class.jq_Method;

import chord.util.FileUtils;
import chord.program.MethodSign;
import chord.program.Program;
import chord.doms.DomM;
import chord.project.Chord;
import chord.project.Project;
import chord.project.analyses.JavaAnalysis;
import chord.project.analyses.ProgramRel;

/**
 *
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
@Chord(
	name = "src-files-java"
)
public class SrcFilesAnalysis extends JavaAnalysis {
	public void run() {
		String methodsFileName = System.getProperty("chord.reach.methods.file");
		Iterable<jq_Method> methods;
		ProgramRel relReachableM;
		if (methodsFileName != null) {
			relReachableM = null;
			List<String> methodList = FileUtils.readFileToList(methodsFileName);
			Set<jq_Method> methodSet = new HashSet<jq_Method>(methodList.size());
			for (String s : methodList) {
				jq_Method m = Program.getProgram().getMethod(MethodSign.parse(s));
				if (m == null)
					throw new RuntimeException("Method: " + s + " not found");
				methodSet.add(m);
			}
			methods = methodSet;
		} else {
			DomM domM = (DomM) Project.getTrgt("M");
			Project.runTask(domM);
			relReachableM = (ProgramRel) Project.getTrgt("reachableM");
			relReachableM.load();
			methods = relReachableM.getAry1ValTuples();
		}
		Set<String> fileNames = new HashSet<String>();
		Set<jq_Class> seenClasses = new HashSet<jq_Class>();
		long numBytecodes = 0;
		for (jq_Method m : methods) {
			byte[] bc = m.getBytecode();
			if (bc != null) {
				numBytecodes += bc.length;
				System.out.println("METHOD: " + m + " " + bc.length);
			}
			jq_Class c = m.getDeclaringClass();
			if (seenClasses.add(c)) {
				if (c.getName().contains("$"))
					continue;
				String fileName = Program.getSourceFileName(c);
				if (fileName == null) {
					System.out.println("WARNING: file not found for class: " + c);
					continue;
				}
				fileNames.add(fileName);
			}
		}
		for (jq_Class c : seenClasses)
			System.out.println("CLASS: " + c);
		if (methodsFileName == null)
			relReachableM.close();
		System.out.println("NUM BYTECODES: " + numBytecodes);
		for (String fileName : fileNames) {
			System.out.println("FILE: " + fileName);
		}
	}
}
