/*
 * Copyright (c) 2008-2010, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 * Licensed under the terms of the New BSD License.
 */
package chord.project;

import java.io.File;
import java.io.IOException;
import chord.util.FileUtils;

/**
 * System properties recognized by Chord.
 *
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
public class Config {
	private static final String BAD_OPTION = "ERROR: Unknown value '%s' for system property '%s'; expected: %s";

	private Config() { }

	// basic properties about program being analyzed (its main class, classpath, command line args, etc.)

	public final static String workDirName = System.getProperty("chord.work.dir");
	public final static String mainClassName = System.getProperty("chord.main.class");
	public final static String userClassPathName = System.getProperty("chord.class.path");
	public final static String srcPathName = System.getProperty("chord.src.path");
	public final static String runIDs = System.getProperty("chord.run.ids", "0");
	public final static String runtimeJvmargs = System.getProperty("chord.runtime.jvmargs", "-ea -Xmx1024m");

	// properties concerning how the program's analysis scope is constructed

	public final static String scopeKind = System.getProperty("chord.scope.kind", "rta");
	public final static String reflectKind = System.getProperty("chord.reflect.kind", "none");
	public final static String CHkind = System.getProperty("chord.ch.kind", "static");
	static {
		check(scopeKind, new String[] { "rta", "cha", "dynamic" }, "chord.scope.kind");
		check(CHkind, new String[] { "static", "dynamic" }, "chord.ch.kind");
		check(reflectKind, new String[] { "none", "static", "dynamic", "static_cast" }, "chord.reflect.kind");
	}
	public final static boolean doSSA = buildBoolProperty("chord.ssa", true);
	public final static String toolClassPathPackages = "chord.,javassist.,joeq.,net.sf.bddbddb.,net.sf.javabdd.,jdom.";
	public final static String DEFAULT_SCOPE_EXCLUDES =
		concat(toolClassPathPackages, ',', "sun.,com.sun.,com.ibm.,org.apache.harmony.");
	public final static String scopeStdExcludeStr = System.getProperty("chord.std.scope.exclude", DEFAULT_SCOPE_EXCLUDES);
	public final static String scopeExtExcludeStr = System.getProperty("chord.ext.scope.exclude", "");
	public static String scopeExcludeStr =
		System.getProperty("chord.scope.exclude", concat(scopeStdExcludeStr, ',', scopeExtExcludeStr));
	public final static String DEFAULT_CHECK_EXCLUDES =
		concat(toolClassPathPackages, ',', "java.,javax.,sun.,com.sun.,com.ibm.,org.apache.harmony.");
	public final static String checkStdExcludeStr = System.getProperty("chord.std.check.exclude", DEFAULT_CHECK_EXCLUDES);
	public final static String checkExtExcludeStr = System.getProperty("chord.ext.check.exclude", "");
	public final static String checkExcludeStr = System.getProperty("chord.check.exclude",
		concat(checkStdExcludeStr, ',', checkExtExcludeStr));

	// properties dictating what gets computed/printed by Chord

	public final static boolean buildScope = buildBoolProperty("chord.build.scope", false);
	public final static String runAnalyses = System.getProperty("chord.run.analyses", "");
	public final static String printMethods = System.getProperty("chord.print.methods", "").replace('#', '$');
	public final static String printClasses = System.getProperty("chord.print.classes", "").replace('#', '$');
	public final static boolean printAllClasses = buildBoolProperty("chord.print.all.classes", false);
	public final static String printRels = System.getProperty("chord.print.rels", "");
	public final static boolean printProject = buildBoolProperty("chord.print.project", false);
	public final static boolean printResults = buildBoolProperty("chord.print.results", true);
	public final static boolean saveDomMaps = buildBoolProperty("chord.save.maps", true);
	// Determines verbosity level of Chord:
	// 0 => silent
	// 1 => print task/process enter/leave/time messages and sizes of computed doms/rels
	//      bddbddb: print sizes of relations output by solver
	// 2 => all other messages in Chord
	//      bddbddb: print bdd node resizing messages, gc messages, and solver stats (e.g. how long each iteration took)
	// 3 => bddbddb: noisy=yes for solver
	// 4 => bddbddb: tracesolve=yes for solver
	// 5 => bddbddb: fulltravesolve=yes for solver
	public final static int verbose = Integer.getInteger("chord.verbose", 1);

	// Chord project properties

	public final static String stdJavaAnalysisPathName = System.getProperty("chord.std.java.analysis.path");
	public final static String extJavaAnalysisPathName = System.getProperty("chord.ext.java.analysis.path");
	public final static String javaAnalysisPathName = System.getProperty("chord.java.analysis.path");
	public final static String stdDlogAnalysisPathName = System.getProperty("chord.std.dlog.analysis.path");
	public final static String extDlogAnalysisPathName = System.getProperty("chord.ext.dlog.analysis.path");
	public final static String dlogAnalysisPathName = System.getProperty("chord.dlog.analysis.path");
	public final static boolean classic = System.getProperty("chord.classic").equals("true");

	// properties specifying configuration of instrumentation and dynamic analysis

	public final static boolean useJvmti = buildBoolProperty("chord.use.jvmti", false);
	public final static String instrKind = System.getProperty("chord.instr.kind", "offline");
	public final static String traceKind = System.getProperty("chord.trace.kind", "full");
	static {
		check(instrKind, new String[] { "offline", "online" }, "chord.instr.kind");
		check(traceKind, new String[] { "none", "full", "pipe" }, "chord.trace.kind");
	}
	public final static boolean dynamicHaltOnErr = buildBoolProperty("chord.dynamic.haltonerr", true);
	public final static int dynamicTimeout = Integer.getInteger("chord.dynamic.timeout", -1);
	public final static int maxConsSize = Integer.getInteger("chord.max.cons.size", 50000000);

	// properties concerning aspects that can affect Chord's performance

	public final static String maxHeap = System.getProperty("chord.max.heap");
	public final static String maxStack = System.getProperty("chord.max.stack");
	public final static String jvmargs = System.getProperty("chord.jvmargs");
	public final static int traceBlockSize = Integer.getInteger("chord.trace.block.size", 4096);
	public final static String bddbddbMaxHeap = System.getProperty("chord.bddbddb.max.heap", "1024m");
	public final static boolean useBuddy = buildBoolProperty("chord.use.buddy", false);

	// properties dictating what is reused across Chord runs

	public final static boolean reuseScope = buildBoolProperty("chord.reuse.scope", false);
	public final static boolean reuseRels = buildBoolProperty("chord.reuse.rels", false);
	public final static boolean reuseTraces = buildBoolProperty("chord.reuse.traces", false);

	// properties specifying names of Chord's output files and directories

	public final static String outFileName = System.getProperty("chord.out.file", null);
	public final static String errFileName = System.getProperty("chord.err.file", null);
	public final static String outDirName = System.getProperty("chord.out.dir", workRel2Abs("chord_output"));
	public final static String reflectFileName = System.getProperty("chord.reflect.file", outRel2Abs("reflect.txt"));
	public final static String methodsFileName = System.getProperty("chord.methods.file", outRel2Abs("methods.txt"));
	public final static String classesFileName = System.getProperty("chord.classes.file", outRel2Abs("classes.txt"));
	public final static String bddbddbWorkDirName = System.getProperty("chord.bddbddb.work.dir", outRel2Abs("bddbddb"));
	public final static String bootClassesDirName = System.getProperty("chord.boot.classes.dir", outRel2Abs("boot_classes"));
	public final static String userClassesDirName = System.getProperty("chord.user.classes.dir", outRel2Abs("user_classes"));
	public final static String instrSchemeFileName = System.getProperty("chord.instr.scheme.file", outRel2Abs("scheme.ser"));
	public final static String traceFileName = System.getProperty("chord.trace.file", outRel2Abs("trace"));

	static {
		FileUtils.mkdirs(outDirName);
		FileUtils.mkdirs(bddbddbWorkDirName);
	}

	// commonly-used constants

	public final static String LIST_SEPARATOR = " |,|:|;";
	public final static String mainDirName = System.getProperty("chord.main.dir");
	public final static String javaClassPathName = System.getProperty("java.class.path");
	public final static String toolClassPathName =
		FileUtils.makePath(new String[] { mainDirName + File.separator + "chord.jar", javaAnalysisPathName });
	public final static String stubsFileName = "chord/program/stubs/stubs.txt";
	// This source of this agent is defined in main/agent/chord_instr_agent.cpp.
	// See the ccompile target in main/build.xml and main/agent/Makefile for how it is built.
	public final static String cInstrAgentFileName = mainDirName + File.separator + "libchord_instr_agent.so";
	// This source of this agent is defined in main/src/chord/instr/OnlineTransformer.java.
	// See the jcompile target in main/build.xml for how it is built.
	public final static String jInstrAgentFileName = mainDirName + File.separator + "chord.jar";
	public final static String javadocURL = "http://chord.stanford.edu/javadoc/";

	public static String[] scopeExcludeAry = toArray(scopeExcludeStr);
	public static boolean isExcludedFromScope(String typeName) {
		for (String c : scopeExcludeAry)
			if (typeName.startsWith(c))
				return true;
		return false;
	}
	public final static String[] checkExcludeAry = toArray(checkExcludeStr);
	public static boolean isExcludedFromCheck(String typeName) {
		for (String c : checkExcludeAry)
			if (typeName.startsWith(c))
				return true;
		return false;
	}

	public static void print() {
		System.out.println("java.vendor: " + System.getProperty("java.vendor"));
		System.out.println("java.version: " + System.getProperty("java.version"));
		System.out.println("os.arch: " + System.getProperty("os.arch"));
		System.out.println("os.name: " + System.getProperty("os.name"));
		System.out.println("os.version: " + System.getProperty("os.version"));
		System.out.println("java.class.path: " + javaClassPathName);
		System.out.println("chord.main.dir: " + mainDirName);
		System.out.println("chord.work.dir: " + workDirName);
		System.out.println("chord.main.class: " + mainClassName);
		System.out.println("chord.class.path: " + userClassPathName);
		System.out.println("chord.src.path: " + srcPathName);
		System.out.println("chord.run.ids: " + runIDs);
		System.out.println("chord.runtime.jvmargs: " + runtimeJvmargs);
		System.out.println("chord.scope.kind: " + scopeKind);
		System.out.println("chord.reflect.kind: " + reflectKind);
		System.out.println("chord.ch.kind: " + CHkind);
		System.out.println("chord.ssa: " + doSSA);
		System.out.println("chord.std.scope.exclude: " + scopeStdExcludeStr);
		System.out.println("chord.ext.scope.exclude: " + scopeExtExcludeStr);
		System.out.println("chord.scope.exclude: " + scopeExcludeStr);
		System.out.println("chord.std.check.exclude: " + checkStdExcludeStr);
		System.out.println("chord.ext.check.exclude: " + checkExtExcludeStr);
		System.out.println("chord.check.exclude: " + checkExcludeStr);
		System.out.println("chord.build.scope: " + buildScope);
		System.out.println("chord.run.analyses: " + runAnalyses);
		System.out.println("chord.print.all.classes: " + printAllClasses);
		System.out.println("chord.print.methods: " + printMethods);
		System.out.println("chord.print.classes: " + printClasses);
		System.out.println("chord.print.rels: " + printRels);
		System.out.println("chord.print.project: " + printProject);
		System.out.println("chord.print.results: " + printResults);
		System.out.println("chord.save.maps: " + saveDomMaps);
		System.out.println("chord.verbose: " + verbose);
		System.out.println("chord.std.java.analysis.path: " + stdJavaAnalysisPathName);
		System.out.println("chord.ext.java.analysis.path: " + extJavaAnalysisPathName);
		System.out.println("chord.java.analysis.path: " + javaAnalysisPathName);
		System.out.println("chord.std.dlog.analysis.path: " + stdDlogAnalysisPathName);
		System.out.println("chord.ext.dlog.analysis.path: " + extDlogAnalysisPathName);
		System.out.println("chord.dlog.analysis.path: " + dlogAnalysisPathName);
		System.out.println("chord.classic: " + classic);
		System.out.println("chord.use.jvmti: " + useJvmti);
		System.out.println("chord.instr.kind: " + instrKind);
		System.out.println("chord.trace.kind: " + traceKind);
		System.out.println("chord.dynamic.haltonerr: " + dynamicHaltOnErr);
		System.out.println("chord.dynamic.timeout: " + dynamicTimeout);
		System.out.println("chord.max.cons.size: " + maxConsSize);
		System.out.println("chord.max.heap: " + maxHeap);
		System.out.println("chord.max.stack: " + maxStack);
		System.out.println("chord.jvmargs: " + jvmargs);
		System.out.println("chord.trace.block.size: " + traceBlockSize);
		System.out.println("chord.bddbddb.max.heap: " + bddbddbMaxHeap);
		System.out.println("chord.use.buddy: " + useBuddy);
		System.out.println("chord.reuse.scope: " + reuseScope);
		System.out.println("chord.reuse.rels: " + reuseRels);
		System.out.println("chord.reuse.traces: " + reuseTraces);
	}

    public static String outRel2Abs(String fileName) {
        return (fileName == null) ? null : FileUtils.getAbsolutePath(outDirName, fileName);
    }

    public static String workRel2Abs(String fileName) {
        return (fileName == null) ? null : FileUtils.getAbsolutePath(workDirName, fileName);
    }

	public static boolean buildBoolProperty(String propName, boolean defaultVal) {
		return System.getProperty(propName, Boolean.toString(defaultVal)).equals("true"); 
	}

	public static String[] toArray(String str) {
		return str.equals("") ? new String[0] : str.split(LIST_SEPARATOR);
	}

	private static String concat(String s1, char sep, String s2) {
		if (s1.equals("")) return s2;
		if (s2.equals("")) return s1;
		return s1 + sep + s2;
	}

	private static void check(String val, String[] legalVals, String key) {
		for (String s : legalVals) {
			if (val.equals(s))
				return;
		}
		String legalValsStr = "[ ";
		for (String s : legalVals)
			legalValsStr += s + " ";
		legalValsStr += "]";
		Messages.fatal(BAD_OPTION, val, key, legalValsStr);
	}
}