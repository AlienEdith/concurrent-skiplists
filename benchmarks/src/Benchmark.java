import java.io.*;
import java.util.*;

public class Benchmark {
	static class Context {
		int numThreads;
		int averageOver;

		public Context(int numThreads, int averageOver) {
			this.numThreads = numThreads;
			this.averageOver = averageOver;
		}
	}

	static TreeMap<Integer, List<Context>> contexts; // Key: numOps.

	static void setupContexts() {
		contexts = new TreeMap<>();
		List<Context> c;

		c = new ArrayList<>();
		contexts.put(10, c);
		c.add(new Context(1, 5)); // change averages to: 1000, 1000, 1000, 1000
		c.add(new Context(10, 5));
		c.add(new Context(100, 5));

		/* Comment out to run other benchmarks.
		   10000 ops takes long time to run.
		 */

		/*
		c = new ArrayList<>();
		contexts.put(100, c);
		c.add(new Context(1, 5)); // change averages to: 1000, 1000, 100, 10
		c.add(new Context(10, 5));
		c.add(new Context(100, 5));

		c = new ArrayList<>();
		contexts.put(1000, c);
		c.add(new Context(1, 5)); // 100, 100, 100, 10
		c.add(new Context(10, 5));
		c.add(new Context(100, 5));

		c = new ArrayList<>();
		contexts.put(10000, c);
		c.add(new Context(1, 1)); // 100, 100, 100, 10
		c.add(new Context(10, 1));
		c.add(new Context(100, 1));
		*/
	}

	static TreeMap<String, SkipListInterface.SkipListInterface> newSkipLists() {
		TreeMap<String, SkipListInterface.SkipListInterface> ret = new TreeMap<>();

		ret.put("standard", new StandardLibrary.SkipList());
		ret.put("coarse0", new CoarseGrained.SkipList());

		// Skip (not needed)
		// ret.put("coarse1", new CoarseGrainedImproved.SkipList());

		ret.put("fine0", new FineGrained.SkipList());
		ret.put("fine1", new FineGrainedImproved.SkipList());
		ret.put("lockfree0", new LockFree.SkipList());

		return ret;
	}

	public static void main(String args[]) {
		setupContexts();

		long unixTime = System.currentTimeMillis() / 1000L;

		runAll(Args.Random, "random", unixTime);
		runAll(Args.SameGet, "same_get", unixTime);
	}

	static void runAll(Args argType, String dirname, long unixTime) {
		String dirPrefix = String.format("data/%d/%s/ops", unixTime, dirname);
		File fdir = new File(dirPrefix);
		fdir.mkdirs();

		for (Map.Entry<Integer, List<Context>> entry : contexts.entrySet()) {
			int numOps = entry.getKey();
			List<Context> list = entry.getValue();

			TreeMap<Integer, TreeMap<String, Double>> rows = new TreeMap<>();
			for (Context c : list) {
				Instance inst = new Instance(numOps, c.numThreads, c.averageOver, argType);
				rows.put(c.numThreads, inst.runAll());
			}

			System.out.println("writing tsv " + (new Integer(numOps)).toString());

			writeTsv(dirPrefix, numOps, rows);
		}
	}

	static void writeTsv(String dirPrefix, Integer numOps, TreeMap<Integer, TreeMap<String, Double>> rows) {
		File fdir = new File(dirPrefix, numOps.toString());
		fdir.mkdirs();
		File file = new File(fdir, "data.tsv");

		Writer output = null;

		try {
			output = new BufferedWriter(new FileWriter(file));
			StringBuffer sb = makeTsv(rows);
			output.write(sb.toString());
			output.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static StringBuffer makeTsv(TreeMap<Integer, TreeMap<String, Double>> rows) {
		StringBuffer sb = new StringBuffer();
		boolean first = true;
		final String SEP = "\t";

		for (Map.Entry<Integer, TreeMap<String, Double>> entry : rows.entrySet()) {
			// Header.
			if (first) {
				first = false;
				sb.append("threads");
				for (Map.Entry<String, Double> e : entry.getValue().entrySet()) {
					sb.append(SEP);
					sb.append(e.getKey());
				}
				sb.append("\n");
			}

			// Row.
			int t = entry.getKey();
			sb.append(t);
			for (Map.Entry<String, Double> e : entry.getValue().entrySet()) {
				sb.append(SEP);
				sb.append(e.getValue());
			}
			sb.append("\n");
		}

		return sb;
	}

	interface NextKey {
		String nextKey();
	}

	interface NextValue {
		Integer nextValue();
	}

	/**
	 * nextKey always returns random number.
	 */
	static class RandomKey implements NextKey {
		private final Random random;
		private int nThreads;

		public RandomKey(Random random, int nThreads) {
			this.random = random;
			this.nThreads = nThreads;
		}

		@Override
		public String nextKey() {
			int a = (int) Math.floor(this.nThreads/10) + 1;
			return Integer.toString(this.random.nextInt(a));
		}
	}

	/**
	 * nextValue always returns random number.
	 */
	static class RandomValue implements NextValue {
		private final Random random;

		public RandomValue(Random random) {
			this.random = random;
		}

		@Override
		public Integer nextValue() {
			return this.random.nextInt();
		}
	}

	/**
	 * nextKey returns the same key most of the time.
	 */
	static class SameGetKey implements NextKey {
		public static String SAME_KEY = "42";
		private final Random random, priv;
		private int nThreads;

		public SameGetKey(Random random, int nThreads) {
			this.random = random;
			this.priv = new Random();
			this.nThreads = nThreads;
		}

		@Override
		public String nextKey() {
			double r = this.priv.nextDouble();
			if (r <= 0.5) {
				return SAME_KEY;
			} else {
				while (true) {
					int a = (int) Math.floor(this.nThreads/10) + 1;
					if (a == 42) {
						continue;
					}
					return Integer.toString(this.random.nextInt(a));
				}
			}
		}
	}

	static class Runner implements Runnable {
		SkipListInterface.SkipListInterface s;
		int numOps;
		WeightedOp wop;
		NextKey nk;
		NextValue nv;

		public Runner(SkipListInterface.SkipListInterface s, int numOps, NextKey nk, NextValue nv) {
			this.s = s;
			this.numOps = numOps;
			this.wop = new WeightedOp(new Random(System.nanoTime()));
			this.nk = nk;
			this.nv = nv;
		}

		@Override
		public void run() {
			for (int i = 0; i < numOps; i++) {
				System.out.println(i);
				String k = this.nk.nextKey();

				switch (this.wop.next()) {
				case Remove:
					s.remove(k);
					break;
				case Add:
					Integer v = this.nv.nextValue();
					s.add(k, v);
					break;
				case Get:
					s.get(k);
					break;
				}
			}
		}
	}

	enum Args {
		Random,
		SameGet
	}

	static class Instance {
		int numOps;
		int numThreads;
		int averageOver;
		Args argType;

		Instance(int numOps, int numThreads, int averageOver, Args argType) {
			this.numOps = numOps;
			this.numThreads = numThreads;
			this.averageOver = averageOver;
			this.argType = argType;
		}

		long runOne(SkipListInterface.SkipListInterface s) {
			List<Thread> threads = new ArrayList<>();

			for (int i = 0; i < this.numThreads; i++) {
				Random r = new Random(); // Each thread should have its own random.
				NextKey nk = null;
				switch (this.argType) {
				case Random:
					nk = new RandomKey(r, this.numThreads);
					break;
				case SameGet:
					nk = new SameGetKey(r, this.numThreads);
					break;
				default:
					throw new Error("unhandled arg type");
				}
				NextValue nv = new RandomValue(r);

				Thread t = new Thread(new Runner(s, numOps, nk, nv));
				threads.add(t);
			}

			long start = System.nanoTime();
			for (Thread t : threads) {
				t.start();
			}
			for (Thread t : threads) {
				try {
					t.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			return System.nanoTime() - start;
		}

		double average(List<Long> a) {
			long sum = 0;
			for (Long num : a) {
				sum += num;
			}
			return ((double) sum)/a.size();
		}

		/**
		 * @return TreeMap<algo,time>
		 */
		TreeMap<String, Double> runAll() {
			TreeMap<String, SkipListInterface.SkipListInterface> tm = newSkipLists();
			TreeMap<String, Double> ret = new TreeMap<>();

			for (Map.Entry<String, SkipListInterface.SkipListInterface> e : tm.entrySet()) {
				String algo = e.getKey();
				SkipListInterface.SkipListInterface s = e.getValue();

				List<Long> times = new ArrayList<>();
				for (int i = 0; i < this.averageOver; i++) {
					if (this.argType == Args.SameGet) {
						s.add(SameGetKey.SAME_KEY, 0); // Value does not matter.
					}

					long t = runOne(s);
					times.add(t);
				}

				double avgTime = average(times);
				System.out.println("DONE " + avgTime + " " + algo + " ");
				ret.put(algo, avgTime);
			}

			return ret;
		}
	}
}

enum Op {
	Get,
	Add,
	Remove,
}

class WeightedOp {
	private final Random random;

	public WeightedOp(Random random) {
		this.random = random;
	}

	public Op next() {
		double value = random.nextDouble();

		if (value < 0.5) {
			return Op.Add;
		} else if (value < 1.0) {
			return Op.Remove;
		} else {
			throw new Error("not reached");
		}
	}
}
