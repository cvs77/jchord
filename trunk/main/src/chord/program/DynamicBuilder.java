/*
 * Copyright (c) 2008-2010, Intel Corporation.
 * Copyright (c) 2006-2007, The Trustees of Stanford University.
 * All rights reserved.
 * Licensed under the terms of the New BSD License.
 */
package chord.program;

import java.util.List;
import java.util.Collections;

import chord.util.IndexSet;
 
import joeq.Main.HostedVM;
import joeq.Class.jq_Class;
import joeq.Class.jq_Array;
import joeq.Class.jq_Reference;
import joeq.Class.jq_Method;
import joeq.Compiler.Quad.Quad;
import chord.program.reflect.DynamicReflectResolver;

/**
 * Dynamic analysis-based scope builder.
 *
 * Constructs scope by running the given Java program on the given input,
 * observing which classes are loaded (either using JVMTI or load-time bytecode
 * instrumentation, depending upon whether property {@code chord.use.jvmti}
 * is set to true or false, respectively), and then regarding all methods
 * declared in those classes as reachable.
 *
 * This scope builder does not currently resolve any reflection; use RTA instead.
 *
 * @author Mayur Naik (mhn@cs.stanford.edu)
 */
public class DynamicBuilder implements ScopeBuilder {
	private IndexSet<jq_Method> methods;

	@Override
	public IndexSet<jq_Method> getMethods() {
		if (methods != null)
			return methods;
		Program program = Program.g();
		List<String> classNames = program.getDynamicallyLoadedClasses();
		HostedVM.initialize();
		methods = new IndexSet<jq_Method>();
		for (String s : classNames) {
			jq_Class c = (jq_Class) program.loadClass(s);
			for (jq_Method m : c.getDeclaredStaticMethods()) {
				if (!m.isAbstract())
					m.getCFG();
				methods.add(m);
			}
			for (jq_Method m : c.getDeclaredInstanceMethods()) {
				if (!m.isAbstract())
					m.getCFG();
				methods.add(m);
			}
		}
		return methods;
	}

	@Override
	public Reflect getReflect() {
		return new Reflect();
	}
}
