/*
 * Copyright (c) 2008-2009, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 */
package chord.project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

import com.java2html.Java2HTML;

import chord.util.IndexHashSet;
import chord.util.ProcessExecutor;
import chord.util.FileUtils;
import chord.util.IndexSet;
import chord.util.ArraySet;
 
import joeq.Class.PrimordialClassLoader;
import joeq.Util.Templates.ListIterator;
import joeq.UTF.Utf8;
import joeq.Class.jq_Class;
import joeq.Class.jq_Field;
import joeq.Class.jq_Method;
import joeq.Class.jq_Type;
import joeq.Compiler.Quad.BasicBlock;
import joeq.Compiler.Quad.ControlFlowGraph;
import joeq.Compiler.Quad.Inst;
import joeq.Compiler.Quad.Operator;
import joeq.Compiler.Quad.Quad;
import joeq.Compiler.Quad.Operand;
import joeq.Compiler.Quad.Operand.ParamListOperand;
import joeq.Compiler.Quad.Operand.RegisterOperand;
import joeq.Compiler.Quad.Operator.Move;
import joeq.Compiler.Quad.Operator.ALoad;
import joeq.Compiler.Quad.Operator.AStore;
import joeq.Compiler.Quad.Operator.Getfield;
import joeq.Compiler.Quad.Operator.Getstatic;
import joeq.Compiler.Quad.Operator.Invoke;
import joeq.Compiler.Quad.Operator.Monitor;
import joeq.Compiler.Quad.Operator.New;
import joeq.Compiler.Quad.Operator.NewArray;
import joeq.Compiler.Quad.Operator.Putfield;
import joeq.Compiler.Quad.Operator.Putstatic;
import joeq.Main.Helper;

/**
 * Representation of a Java program.
 * 
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
public class Program {
	private static final Program instance = new Program();
	public static Program v() { return instance; }
	private boolean isInited;
	private IndexHashSet<jq_Class> preparedClasses;
	private IndexHashSet<jq_Method> reachableMethods;
	private IndexHashSet<jq_Type> reachableTypes;
	private Map<String, jq_Class> nameToClassMap;
	private Map<jq_Class, List<jq_Method>> classToMethodsMap;
	private jq_Method mainMethod;
	private IndexSet<jq_Method> rootMethods;
	private boolean HTMLizedJavaSrcFiles;
	private final Map<Inst, jq_Method> instToMethodMap = 
		new HashMap<Inst, jq_Method>();

	public void init() {
		if (isInited)
			return;
		try {
			boolean filesExist =
				(new File(Properties.bootClassesFileName)).exists() &&
				(new File(Properties.classesFileName)).exists() &&
				(new File(Properties.methodsFileName)).exists();
			if (Properties.reuseScope && filesExist)
				initFromCache();
			else {
				String scopeKind = Properties.scopeKind;
				if (scopeKind.equals("rta")) {
					init(new RTA());
				} else if (scopeKind.equals("cha")) {
					init(new CHA());
				} else if (scopeKind.equals("dynamic")) {
					initFromDynamic();
				} else
					assert (false);
			}
		} catch (IOException ex) {
			throw new ChordRuntimeException(ex);
		}
		isInited = true;
	}
	private void loadAndPrepare(String fileName,
			boolean mustBeSystemClass) throws IOException {
		BufferedReader r = new BufferedReader(new FileReader(fileName));
		String s;
		while ((s = r.readLine()) != null) {
			System.out.println("Loading: " + s);
			jq_Class c = (jq_Class) Helper.load(s);
			assert (c != null);
			c.prepare();
			assert(c.isSystemClass() == mustBeSystemClass);
			preparedClasses.add(c);
		}
		r.close();
	}
	private void initFromCache() throws IOException {
		preparedClasses = new IndexHashSet<jq_Class>();
		loadAndPrepare(Properties.bootClassesFileName, true);
		loadAndPrepare(Properties.classesFileName, false);
		buildReachableTypes();
		reachableMethods = new IndexHashSet<jq_Method>();
		BufferedReader r = new BufferedReader(
			new FileReader(Properties.methodsFileName));
		String s;
		while ((s = r.readLine()) != null) {
			int sep1 = s.indexOf(':');
			int sep2 = s.indexOf('@', sep1 + 1);
			String mName = s.substring(0, sep1);
			String mDesc = s.substring(sep1 + 1, sep2);
			String cName = s.substring(sep2 + 1);
			jq_Class c = getPreparedClass(cName);
			assert (c != null);
			jq_Method m = (jq_Method) c.getDeclaredMember(mName, mDesc);
			reachableMethods.add(m);
		}
		r.close();
		String mainClassName = Properties.mainClassName;
		List<String> rootsList = readRootsFile();
		if (mainClassName != null) {
			assert (rootsList == null);
			mainMethod = getReachableMethod(
				"main", "([Ljava/lang/String;)V", mainClassName);
			assert (mainMethod != null);
		} else {
			assert (rootsList != null);
			rootMethods = new ArraySet<jq_Method>(rootsList.size());
			for (String root : rootsList) {
				MethodSign sign = MethodSign.parse(root);
				jq_Method m = getReachableMethod(sign);
				assert (m != null);
				rootMethods.add(m);
			}
		}
	}

	private List<String> readRootsFile() {
		String rootsFileName = Properties.rootsFileName;
		if (rootsFileName == null)
			return null;
		return FileUtils.readFileToList(rootsFileName);
	}

	private void init(AbstractBootstrapper bootstrapper) throws IOException {
		String mainClassName = Properties.mainClassName;
		List<String> rootsList = readRootsFile();
		List<MethodSign> rootMethodSigns = null;
		MethodSign mainMethodSign;
		if (mainClassName != null) {
			assert (rootsList == null);
			mainMethodSign = new MethodSign("main",
				"([Ljava/lang/String;)V", mainClassName);
			rootMethodSigns = new ArrayList<MethodSign>(1);
			rootMethodSigns.add(mainMethodSign);
		} else {
			assert (rootsList != null);
			mainMethodSign = null;
			rootMethodSigns = new ArrayList<MethodSign>(rootsList.size());
			for (String root : rootsList) {
				MethodSign sign = MethodSign.parse(root);
				rootMethodSigns.add(sign);
			}
		}
		bootstrapper.run(rootMethodSigns);
		preparedClasses = bootstrapper.getPreparedClasses();
		reachableMethods = bootstrapper.getReachableMethods();
		write();
		if (mainMethodSign != null) {
			mainMethod = getReachableMethod(mainMethodSign);
		} else {
			rootMethods = new ArraySet<jq_Method>(rootMethodSigns.size());
			for (MethodSign sign : rootMethodSigns) {
				jq_Method m = getReachableMethod(sign);
				assert (m != null);
				rootMethods.add(m);
			}
		}
	}
	private void initFromDynamic() throws IOException {
		String mainClassName = Properties.mainClassName;
		assert (mainClassName != null);
		String classPathName = Properties.classPathName;
		assert (classPathName != null);
        String[] runIDs = Properties.runIDs.split(",");
		assert(runIDs.length > 0);
        final String cmd = "java -ea " +
            " -cp " + classPathName +
            " -agentpath:" + Properties.instrAgentFileName +
            "=boot_classes_file_name=" + Properties.bootClassesFileName +
            "=classes_file_name=" + Properties.classesFileName +
            " " + mainClassName + " ";
		preparedClasses = new IndexHashSet<jq_Class>();
        for (String runID : runIDs) {
            System.out.println("Processing Run ID: " + runID);
            String args = System.getProperty("chord.args." + runID, "");
			ProcessExecutor.execute(cmd + args);
			loadAndPrepare(Properties.bootClassesFileName, true);
			loadAndPrepare(Properties.classesFileName, false);
		}
		reachableMethods = new IndexHashSet<jq_Method>();
		for (jq_Class c : preparedClasses) {
			for (jq_Method m : c.getDeclaredInstanceMethods()) 
				reachableMethods.add(m);
			for (jq_Method m : c.getDeclaredStaticMethods()) 
				reachableMethods.add(m);
		}
		write();
		mainMethod = getReachableMethod(
			"main", "([Ljava/lang/String;)V", mainClassName);
	}
	private void write() throws IOException {
		PrintWriter bootClassesFileWriter =
			new PrintWriter(Properties.bootClassesFileName);
		PrintWriter classesFileWriter =
			new PrintWriter(Properties.classesFileName);
		for (jq_Class c : preparedClasses) {
			if (c.isSystemClass())
				bootClassesFileWriter.println(c);
			else
				classesFileWriter.println(c);
		}
		bootClassesFileWriter.close();
		classesFileWriter.close();
		buildReachableTypes();
		PrintWriter methodsFileWriter =
			new PrintWriter(Properties.methodsFileName);
		for (jq_Method m : reachableMethods)
			methodsFileWriter.println(m);
		methodsFileWriter.close();
	}


	public void mapInstToMethod(Inst i, jq_Method m) {
		instToMethodMap.put(i, m);
	}

	public jq_Method getMethod(Inst i) {
		jq_Method m = instToMethodMap.get(i);
		if (m == null) {
			throw new RuntimeException(
				"Cannot find method containing inst: " + i);
		}
		return m;
	}

	private void buildReachableTypes() {
		reachableTypes = new IndexHashSet<jq_Type>();
		for (jq_Type t : PrimordialClassLoader.loader.getAllTypes()) {
			if (t != null)
				reachableTypes.add(t);
		}
	}
	
	private void buildNameToClassMap() {
		nameToClassMap = new HashMap<String, jq_Class>();
		for (jq_Type t : reachableTypes) {
			if (t instanceof jq_Class) {
				jq_Class c = (jq_Class) t;
				nameToClassMap.put(t.getName(), c);
			}
		}
	}
	
	private void buildClassToMethodsMap() {
		classToMethodsMap = new HashMap<jq_Class, List<jq_Method>>();
		for (jq_Method m : reachableMethods) {
			jq_Class c = m.getDeclaringClass();
			assert (preparedClasses.contains(c));
			List<jq_Method> methods = classToMethodsMap.get(c);
			if (methods == null) {
				methods = new ArrayList<jq_Method>();
				classToMethodsMap.put(c, methods);
			}
			methods.add(m);
		}
	}
	
	public IndexHashSet<jq_Class> getPreparedClasses() {
		return preparedClasses;
	}
	
	public IndexHashSet<jq_Method> getReachableMethods() { 
		return reachableMethods;
	}
	
	public IndexHashSet<jq_Type> getReachableTypes() {
		return reachableTypes;
	}
	
	public jq_Class getPreparedClass(String name) {
		if (nameToClassMap == null)
			buildNameToClassMap();
		return nameToClassMap.get(name);
	}
	
	public List<jq_Method> getReachableMethods(jq_Class c) {
		if (classToMethodsMap == null)
			buildClassToMethodsMap();
		List<jq_Method> methods = classToMethodsMap.get(c);
		if (methods == null)
			return Collections.emptyList();
		return methods;
	}

	public jq_Method getReachableMethod(String mName,
			String mDesc, String cName) {
		jq_Class c = getPreparedClass(cName);
		if (c == null)
			return null;
		List<jq_Method> methods = getReachableMethods(c);
		for (jq_Method m : methods) {
			if (m.getName().toString().equals(mName) &&
				m.getDesc().toString().equals(mDesc))
				return m;
		}
		return null;
	}
	
	public jq_Method getReachableMethod(MethodSign sign) {
		return getReachableMethod(sign.mName, sign.mDesc, sign.cName);
	}

	public IndexSet<jq_Method> getRootMethods() {
		return rootMethods;
	}

	public jq_Method getMainMethod() {
		return mainMethod;
	}

	public jq_Method getThreadStartMethod() {
		return getReachableMethod("start", "()V", "java.lang.Thread");
	}

	public static String getSign(jq_Method m) {
		String d = m.getDesc().toString();
		return m.getName().toString() + methodDescToStr(d);
	}
	
	// convert the given method descriptor string to a string
	// denoting the comma-separated list of types of the method's
	// arguments in human-readable form
	// e.g.: convert <tt>([Ljava/lang/String;I)V<tt> to
	// <tt>(java.lang.String[],int)</tt>
	public static String methodDescToStr(String desc) {
		String t = desc.substring(1, desc.indexOf(')'));
		return "(" + typesToStr(t) + ")";
	}
	
	// convert the given bytecode string encoding a (possibly empty)
	// list of types to a string denoting the comma-separated list
	// of those types in human-readable form
	// e.g. convert <tt>[Ljava/lang/String;I</tt> to
	// <tt>java.lang.String[],int</tt>
	public static String typesToStr(String types) {
    	String result = "";
    	boolean needsSep = false;
        while (types.length() != 0) {
            boolean isArray = false;
            int numDim = 0;
            String baseType;
            // Handle array case
            while(types.startsWith("[")) {
            	isArray = true;
            	numDim++;
            	types = types.substring(1);
            }
            // Determine base type
            if (types.startsWith("B")) {
            	baseType = "byte";
            	types = types.substring(1);
            } else if (types.startsWith("C")) {
            	baseType = "char";
            	types = types.substring(1);
            } else if (types.startsWith("D")) {
            	baseType = "double";
            	types = types.substring(1);
            } else if (types.startsWith("F")) {
            	baseType = "float";
            	types = types.substring(1);
            } else if (types.startsWith("I")) {
            	baseType = "int";
            	types = types.substring(1);
            } else if(types.startsWith("J")) {
            	baseType = "long";
            	types = types.substring(1);
            } else if(types.startsWith("L")) {
            	int index = types.indexOf(';');
            	if(index == -1)
            		throw new RuntimeException("Class reference has no ending ;");
            	String className = types.substring(1, index);
            	baseType = className.replace('/', '.');
            	types = types.substring(index + 1);
            } else if(types.startsWith("S")) {
            	baseType = "short";
            	types = types.substring(1);
            } else if(types.startsWith("Z")) {
            	baseType = "boolean";
            	types = types.substring(1);
            } else if(types.startsWith("V")) {
            	baseType = "void";
            	types = types.substring(1);
            } else
            	throw new RuntimeException("Unknown field type!");
            if (needsSep)
            	result += ",";
            result += baseType;
            if (isArray) {
            	for (int i = 0; i < numDim; i++)
            		result += "[]";
            }
            needsSep = true;
        }
        return result;
	}
	
	/**
	 * Dumps this program's Java source files in HTML form.
	 */
	public void HTMLizeJavaSrcFiles() {
		if (!HTMLizedJavaSrcFiles) {
			String srcPathName = Properties.srcPathName;
			assert (srcPathName != null);
			String[] srcDirNames =
				srcPathName.split(File.pathSeparator);
			try {
				Java2HTML java2HTML = new Java2HTML();
				java2HTML.setMarginSize(4);
				java2HTML.setTabSize(4);
				java2HTML.setJavaDirectorySource(srcDirNames);
				java2HTML.setDestination(Properties.outDirName);
				java2HTML.buildJava2HTML();
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
			HTMLizedJavaSrcFiles = true;
		}
	}
	
	public static int getLineNumber(Quad q, jq_Method m) {
		int bci = m.getBCI(q);
		if (bci == -1)
			return 0;
		return m.getLineNumber(bci);
	}
	
	public static int getLineNumber(Inst i, jq_Method m) {
		if (i instanceof Quad)
			return getLineNumber((Quad) i, m);
		return 0;
	}
	
	public static String toString(RegisterOperand op) {
		return "<" + op.getType().getName() + " " + op.getRegister() + ">";
	}

	public static String toString(Operand op) {
		if (op instanceof RegisterOperand)
			return toString((RegisterOperand) op);
		return op.toString();
	}

	public String toStringInvokeInst(Quad q) {
		String s = "";
		RegisterOperand ro = Invoke.getDest(q);
		if (ro != null) 
			s = toString(ro) + " := ";
		else
			s = "";
		jq_Method m = Invoke.getMethod(q).getMethod();
		s += m.getNameAndDesc().toString() + "(";
		ParamListOperand po = Invoke.getParamList(q);
		int n = po.length();
		for (int i = 0; i < n; i++) {
			s += toString(po.get(i));
			if (i < n - 1)
				s += ",";
		}
		s += ")";
		return location(q) + s;
	}
	
	public static String toString(int bci, String mName, String mDesc,
			String cName) {
		return bci + "!" + mName + ":" + mDesc + "@" + cName;
	}
	
	public static String toString(String name, String desc, String cName) {
		return name + ":" + desc + "@" + cName;
	}

	public String toStringNewInst(Quad q) {
		String t, l;
		if (q.getOperator() instanceof New) {
			l = toString(New.getDest(q));
			t = New.getType(q).getType().getName();
		} else {
			l = toString(NewArray.getDest(q));
			t = NewArray.getType(q).getType().getName();
		}
		return location(q) + l + " = new " + t;
	}
	
	public String toStringHeapInst(Quad q) {
		Operator op = q.getOperator();
		String s;
		if (isWrHeapInst(op)) {
			String b, f, r;
			if (op instanceof Putfield) {
				b = toString(Putfield.getBase(q)) + ".";
				f = Putfield.getField(q).getField().toString();
				r = toString(Putfield.getSrc(q));
			} else if (op instanceof AStore) {
				b = toString(AStore.getBase(q));
				f = "[*]";
				r = toString(AStore.getValue(q));
			} else {
				b = "";
				f = Putstatic.getField(q).getField().toString();
				r = toString(Putstatic.getSrc(q));
			}
			s = b + f + " := " + r;
		} else {
			String l, b, f;
			if (op instanceof Getfield) {
				l = toString(Getfield.getDest(q));
				b = toString(Getfield.getBase(q)) + ".";
				f = Getfield.getField(q).getField().toString();
			} else if (op instanceof ALoad) {
				l = toString(ALoad.getDest(q));
				b = toString(ALoad.getBase(q));
				f = "[*]";
			} else {
				l = toString(Getstatic.getDest(q));
				b = "";
				f = Getstatic.getField(q).getField().toString();
			}
			s = l + " := " + b + f;
			
		}
		return location(q) + s;
	}
	
	public String toStringLockInst(Inst q) {
		jq_Method m = getMethod(q);
		String fileName = getSourceFileName(m.getDeclaringClass());
		int lineNumber = getLineNumber(q, m);
		String s;
		if (q instanceof Quad)
			s = Monitor.getSrc((Quad) q).toString();
		else
			s = getMethod(q).toString();
		return fileName + ":" + lineNumber + ": monitorenter " + s;
	}

	public String location(Quad q) {
		jq_Method m = getMethod(q);
		jq_Class c = m.getDeclaringClass();
		String fileName = getSourceFileName(c);
		int lineNumber = getLineNumber(q, m);
		return fileName + ":" + lineNumber + ": ";
	}
	
	public String toString(Quad q) {
		Operator op = q.getOperator();
		if (op instanceof Move) {
			return location(q) + Move.getDest(q) + " := " +
				Move.getSrc(q);
		}
		if (op instanceof Getfield || op instanceof Putfield ||
				op instanceof ALoad || op instanceof AStore ||
				op instanceof Getstatic || op instanceof Putstatic) {
			return toStringHeapInst(q);
		}
		if (op instanceof New || op instanceof NewArray)
			return toStringNewInst(q);
		if (op instanceof Invoke)
			return toStringInvokeInst(q);
		return location(q) + q.toString();
	}
	
	public static jq_Field getField(Quad e) {
		Operator op = e.getOperator();
		if (op instanceof ALoad || op instanceof AStore)
			return null;
		if (op instanceof Getfield)
			return Getfield.getField(e).getField();
		if (op instanceof Putfield)
			return Putfield.getField(e).getField();
		if (op instanceof Getstatic)
			return Getstatic.getField(e).getField();
		if (op instanceof Putstatic)
			return Putstatic.getField(e).getField();
		throw new RuntimeException();
	}
	
	public static boolean isHeapInst(Operator op) {
		return op instanceof ALoad || op instanceof AStore ||
			op instanceof Getfield || op instanceof Putfield ||
			op instanceof Getstatic || op instanceof Putstatic;
	}
	
	public static boolean isWrHeapInst(Operator op) {
		return op instanceof Putfield || op instanceof AStore ||
			op instanceof Putstatic;
	}
	
	public static String getSourceFileName(jq_Class c) {
		String t = c.getName();
		String s = t.substring(0, t.lastIndexOf('.') + 1);
		Utf8 f = c.getSourceFile();
		return s.replace('.', '/') + f;
	}
	
	public void print() {
		for (jq_Method m : getReachableMethods()) {
			System.out.println(m);
			if (!m.isAbstract()) {
				ControlFlowGraph cfg = m.getCFG();
				for (ListIterator.BasicBlock it = cfg.reversePostOrderIterator();
                		it.hasNext();) {
					BasicBlock bb = it.nextBasicBlock();
					for (ListIterator.Quad it2 = bb.iterator(); it2.hasNext();) {
						Quad q = it2.nextQuad();			
						int bci = m.getBCI(q);
						System.out.println("\t" + bci + "#" + q.getID());
					}
				}
				System.out.println(cfg.fullDump());
			}
		}
	}
}
