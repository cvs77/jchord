/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.benchmark.quality.utils;

import java.io.File;
import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.PriorityQueue;

/**
 * Suggest Quality queries based on an index contents.
 * Utility class, used for making quality test benchmarks.
 */
public class QualityQueriesFinder {

  private static final String newline = System.getProperty("line.separator");
  private Directory dir;
  
  /**
   * Constrctor over a directory containing the index.
   * @param dir directory containing the index we search for the quality test. 
   */
  private QualityQueriesFinder(Directory dir) {
    this.dir = dir;
  }

  /**
   * @param args {index-dir}
   * @throws IOException  if cannot access the index.
   */
  public static void main(String[] args) throws IOException {
    if (args.length<1) {
      System.err.println("Usage: java QualityQueriesFinder <index-dir>");
      System.exit(1);
    }
    QualityQueriesFinder qqf = new QualityQueriesFinder(FSDirectory.getDirectory(new File(args[0])));
    String q[] = qqf.bestQueries("body",20);
    for (int i=0; i<q.length; i++) {
      System.out.println(newline+formatQueryAsTrecTopic(i,q[i],null,null));
    }
  }

  private String [] bestQueries(String field,int numQueries) throws IOException {
    String words[] = bestTerms("body",4*numQueries);
    int n = words.length;
    int m = n/4;
    String res[] = new String[m];
    for (int i=0; i<res.length; i++) {
      res[i] = words[i] + " " + words[m+i]+ "  " + words[n-1-m-i]  + " " + words[n-1-i];
      //System.out.println("query["+i+"]:  "+res[i]);
    }
    return res;
  }
  
  private static String formatQueryAsTrecTopic (int qnum, String title, String description, String narrative) {
    return 
      "<top>" + newline +
      "<num> Number: " + qnum             + newline + newline + 
      "<title> " + (title==null?"":title) + newline + newline + 
      "<desc> Description:"               + newline +
      (description==null?"":description)  + newline + newline +
      "<narr> Narrative:"                 + newline +
      (narrative==null?"":narrative)      + newline + newline +
      "</top>";
  }
  
  private String [] bestTerms(String field,int numTerms) throws IOException {
    PriorityQueue pq = new TermsDfQueue(numTerms);
    IndexReader ir = IndexReader.open(dir);
    try {
      int threshold = ir.maxDoc() / 10; // ignore words too common.
      TermEnum terms = ir.terms(new Term(field,""));
      while (terms.next()) {
        if (!field.equals(terms.term().field())) {
          break;
        }
        int df = terms.docFreq();
        if (df<threshold) {
          String ttxt = terms.term().text();
          pq.insert(new TermDf(ttxt,df));
        }
      }
    } finally {
      ir.close();
    }
    String res[] = new String[pq.size()];
    int i = 0;
    while (pq.size()>0) {
      TermDf tdf = (TermDf) pq.pop(); 
      res[i++] = tdf.word;
      System.out.println(i+".   word:  "+tdf.df+"   "+tdf.word);
    }
    return res;
  }

  private static class TermDf {
    String word;
    int df;
    TermDf (String word, int freq) {
      this.word = word;
      this.df = freq;
    }
  }
  
  private static class TermsDfQueue extends PriorityQueue {
    TermsDfQueue (int maxSize) {
      initialize(maxSize);
    }
    protected boolean lessThan(Object a, Object b) {
      TermDf tf1 = (TermDf) a;
      TermDf tf2 = (TermDf) b;
      return tf1.df < tf2.df;
    }
  }
  
}
