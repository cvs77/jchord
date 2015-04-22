# Introduction #

JChord is designed to be run on "closed" programs, where all the code is present at analysis time. However, many interesting programs are "open", with code to be added later. For example, frameworks like Tomcat and Hadoop MapReduce are open. This page discusses ways to cope.


# Analyzing Open Programs #

Often, the best way to cope is to create an explicit program that acts as a test harness for the framework of interest.  Often, you have to do some of the work by hand. But there's a tool in extra/src/chord/analyses/makestub that will do some of the work for you.

It runs as a JChord task. It requires config option chord.stubs.toMake, which should be a list of classes to generate harnesses for. It will then write a set of classes with names of the form StubXYZ.java, to te chord output directory.

For each class XYZ, the generated harness has one method, exercise(), that takes an object of type XYZ and will call every public method on that object.

The reason for this interface is that often, you care about which object these methods are invoked on. For instance, suppose you're building a harness to invoke RPC methods. You want to ensure that those methods are called on the concrete server object, so that values correctly flow between the remotely-invoked RPC methods and the non-remote initialization code.

Possible extensions include generating harnesses for static methods and exercising constructors.


# Designing Analyses for Open Programs #

The above section discussed running existing analyses on open programs. This section is about developing analyses that cope well with such programs.

In a closed program, all objects will have an allocation site. In an open program, they need not. Ideally, analysis should do something sensible with a pointer that does not reference any abstract object.

An open program can have reachable code not reached through any visible entrypoint. Generating stubs, as discussed above, is one way to cope. It's usually the right way to go.

You might instead try to incorporate these richer notions of reachability into the analysis. This doesn't work as well, in general. Chord's default call-graph and points-to algorithms use the type of the object pointed to by the "this" pointer to figure out which instance method a call will resolve to.