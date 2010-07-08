/*
 * Copyright (c) 2006-07, The Trustees of Stanford University.  All
 * rights reserved.
 * Licensed under the terms of the GNU GPL; see COPYING for details.
 */
package test;

/**
 * @author Omer Tripp (omertrip@post.tau.ac.il)
 */
public class T extends java.lang.Thread {
	
	private B b1, b2;
	
	static class B {
		int f;
		
		public static B getB1() {
//			return new B();
			return getB2();
		}
		
		private static B getB2() {
			return new B();
		}
	}
	
	public T(B b1, B b2) {
		this.b1 = b1;
		this.b2 = b2;
	}
	
    public static void main(String[] a) {
        B b1 = B.getB1();
        B b2 = B.getB1();
        T t1 = new T(b1, b2);
//        T t2 = new T(b1, b2);
        t1.start();
//        t2.start();
    }
    
    @Override
    public void run() {
    	foo();
    	bar();
    }
    
    private void foo() {
    	b1.f = 3;
    }
    
    private void bar() {
    	b2.f = 3;
    }
}
