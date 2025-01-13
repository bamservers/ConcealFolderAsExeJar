package com.xophcorp.conceal;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.zip.*;
import java.util.*;
import java.util.concurrent.*;


/**
 * ConcealFolderAsExeJar
 * Generates a new jar file given the folder as input. Executable jar will contain contents of the folder, but have a front-end when double clicked on. Can also provide a contents viewer to show what's inside.
 * 
 */
public class ConcealFolderAsExeJar
{
	private String directoryToConcealPath;// root of the files
	
	// buffer cache map used by consumeStream(), one for every output stream
	private static final int BUFFER_SIZE = 1024 * 1024 * 4;
	private final int BUCKET_COUNT = 4;// Can use one per worker thread
	private HashMap<Integer, byte[]> byteBuffersByOutputObjectHash = new HashMap<Integer, byte[]>();

	private MessageDigest md;// TODO: Split by thread, like the byte buffers. Enclosed in a 'synchronized' block for now, which works.
	private sun.misc.BASE64Encoder base64_encoder = new sun.misc.BASE64Encoder();
	private FileTransferStatusUpdateListener updater;// TODO: Split by thread, like the byte buffers

	public ConcealFolderAsExeJar (String directoryToConcealPath)
	{
		this.directoryToConcealPath = directoryToConcealPath;
		
		updater = new FileTransferStatusUpdateListener()
		{
			public int lastPercent = -1;
			
			@Override
			public void statusUpdate(int byteTotal, long fileSize, long currentTimeMillis)
			{
				double percentTransferred = (byteTotal / (double)fileSize) * 100;
				
				int percent = (int)percentTransferred;
				
				if (percent > lastPercent)
				{
					System.out.print ((int)percentTransferred + "%" + (percent < 100 ? ", " : ""));
					lastPercent = percent;
				}
			}
			
			public void reset()
			{
				lastPercent = -1;
			}
		};
		
		try
		{
			md = java.security.MessageDigest.getInstance(/*"MD5"*/"SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			System.out.println ("Unable to initialize message digest: " + e.toString());
			e.printStackTrace();
		}
	}


	public static void main(String[] args) 
	{
		//listZipContents (new File ("_Dead_Or_Broken.jar"));
		
		// Check the given argument for the desired directory to conceal
		if (args != null && args.length > 0)
		{
			String directoryToConcealPath = args[0] + (args[0].endsWith("\\") == false ? "\\" : "");// add trailing slash for nicer log output
			
			ConcealFolderAsExeJar c = new ConcealFolderAsExeJar (directoryToConcealPath);
			
			c.run();
		}
		// If no arguments were presented, run a simple UI to show the user the file does something.
		else
		{
			System.out.println ("Hello World!");
			
			javax.swing.JOptionPane.showMessageDialog(null, "Hello World!");			
		}
	}
	
	public static void listZipContents (File file)
	{
		try
		{
			FileInputStream fis = new FileInputStream (file);
			BufferedInputStream bis = new BufferedInputStream (fis/*, BUFFER_SIZE*/);
			
			ZipInputStream zis = new ZipInputStream (bis);
			ZipEntry zei = null;
			
	        while ((zei = zis.getNextEntry()) != null)
	        {
	        	// output what we're copying. Skip directories
	        	if (zei.getName().endsWith("/") == false)
	        	{
	        		System.out.println ("File " + zei.getName());
	        	}
	        }
	        
			fis.close();
		}
		catch (Exception e)
		{
			System.out.println ("Unable to list zip contents: " + e.toString());
			e.printStackTrace();
		}
	}
	
	public void run()
	{
		System.out.println("Concealing " + directoryToConcealPath);
		
		// List all the files we're going to collect and jar (recursively)
		final File directoryToConceal = new File (directoryToConcealPath);
		
		// Determine the save path, put it adjacent to the folder we're concealing
		final String parentPath = directoryToConceal.getParentFile().getAbsolutePath() + "\\";
		
				
		try
		{
			final List<File> allFiles = new ArrayList<File>();
			java.nio.file.Files.walkFileTree(Paths.get(directoryToConceal.toURI()), new FileVisitor<Path>()
			{
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {return java.nio.file.FileVisitResult.CONTINUE;}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
				{
					allFiles.add(file.toFile());
					return java.nio.file.FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException	{return java.nio.file.FileVisitResult.CONTINUE;}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {return java.nio.file.FileVisitResult.CONTINUE;}
			});
			
			
			// Split up the files in approprate segments, based on file sizes to make evenly-sized buckets/lists
			final List<File[]> fileBuckets = getFileBuckets (allFiles.toArray(new File [] {}), BUCKET_COUNT);
			
			// Start concealing the folder and its contents
			//File[] files = directoryToConceal.listFiles();			
			
			if (fileBuckets.size() == 1)
			{
				FileOutputStream fos = new FileOutputStream (new File (directoryToConceal.getName() + ".jar"));
				BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE);
				ZipOutputStream zos = new ZipOutputStream(bos);
				NonCloseableOutputStream nos = new NonCloseableOutputStream (zos);// required, so the stream stops from getting closed after each write.

				zos.setLevel(9);// 9 = full, 0 = none
				zos.setMethod(ZipEntry.DEFLATED);// DEFLATED = compressed. STORED = uncompressed
				
				concealFolderIntoZip (fileBuckets.get(0), zos, nos);
				
				zos.setComment("ConcealFolderAsExeJar 1.0");
				zos.flush();
				zos.finish();
				//nos.flush();
				nos.close();				
			}
			else
			{
				// Use Multi-Threading here!
				ForkJoinPool forkJoinPool = new ForkJoinPool(fileBuckets.size());

				List<ForkJoinTask<File>> tasks = new ArrayList<ForkJoinTask<File>>();
				
				// Create a zip for each bucket
				for (int cnt = 0;cnt < fileBuckets.size();cnt++)
				{
					final Integer index = cnt;
					final File[] fileList = fileBuckets.get(cnt);
					
					ForkJoinTask<File> fjt = new ForkJoinTask<File>()
					{
						private File resultFile = new File(parentPath + directoryToConceal.getName() + "." + index + ".jar");
						
						@Override
						public File getRawResult()	{return resultFile;}

						@Override
						protected void setRawResult(File value) {};

						@Override
						protected boolean exec()
						{
							boolean success = false;
							try
							{
								FileOutputStream fos2 = new FileOutputStream (resultFile);
								BufferedOutputStream bos2 = new BufferedOutputStream(fos2, BUFFER_SIZE);
								ZipOutputStream zos2 = new ZipOutputStream(bos2);
								NonCloseableOutputStream nos2 = new NonCloseableOutputStream (zos2);// required, so the stream stops from getting closed after each write.
								
								zos2.setLevel(9);// 9 = full, 0 = none
								zos2.setMethod(ZipEntry.DEFLATED);// DEFLATED = compressed. STORED = uncompressed
			
								concealFolderIntoZip (fileList, zos2, nos2);
								
								zos2.setComment("ConcealFolderAsExeJar 1.0 - Part (" + (index+1) + "/" + fileBuckets.size() + ")");
								zos2.flush();
								zos2.finish();
								//nos.flush();
								nos2.close();
								
								success = true;
							}
							catch (Exception e)
							{
								System.out.println ("Unable to generate zip part " + (index+1) + "/" + fileBuckets.size() + ": " + e.toString());
								e.printStackTrace();
							}
							
							complete (resultFile);
							
							return success;
						}
					};
					
					tasks.add(fjt);
				}

				Collection<ForkJoinTask<File>> resultingTasks = ForkJoinTask.invokeAll(tasks);

				Iterator<ForkJoinTask<File>> taskIterator = resultingTasks.iterator();
				
				while (taskIterator.hasNext())
				{
					ForkJoinTask<File> task = taskIterator.next();
					
					System.out.println ("Task Result: " + task.getRawResult() + " (error? " + (task.getException() != null ? task.getException().toString() : "no") + ")"); 
				}
			}
		}
		catch (Exception e)
		{
			System.out.println ("Problems concealing folder: " + e.toString());
			e.printStackTrace();
		}
	}
	
	private void addExeClasses(ZipOutputStream zos, NonCloseableOutputStream nos, FileTransferStatusUpdateListener updater) throws IOException
	{
		// Add the class files to make the jar executable, if they exist
		File exeJarFile = new File (this.getClass().getSimpleName() + ".jar");
		
		if (exeJarFile.exists())
		{
			// Copy the contents of the jar into the output file.
			// This must be done first, to work well with big jar files. 
			FileInputStream fis = new FileInputStream (exeJarFile);
			BufferedInputStream bis = new BufferedInputStream (fis/*, BUFFER_SIZE*/);
			
			ZipInputStream zis = new ZipInputStream (bis);
			ZipEntry zei = null;
			
            while ((zei = zis.getNextEntry()) != null)
            {
            	// output what we're copying. Skip directories
            	if (zei.getName().endsWith("/") == false)
            	{
            		System.out.println ("Copying " + zei.getName());
            	}

				ZipEntry zep = new ZipEntry (zei.getName());
				zos.putNextEntry(zep);
					            	
            	consumeStream (zis, nos, zep.getCompressedSize(), null);
            	
				// Reset the updater here
				updater.reset();
				
				zos.closeEntry();
            }
            
			fis.close();
		}
	}


	private List<File[]> getFileBuckets(File[] allFiles, int bucketCount)
	{
		class Bucket
		{
			public long totalSize = 0;
			public List<File> files = new ArrayList<File>();
			
			public long addFile(File file)
			{
				this.files.add(file);
				this.totalSize += file.length();
				
				return this.totalSize;
			}
		}
		
		Bucket[] buckets = new Bucket [bucketCount];
		
		// Initialize each bucket
		for (int cnt = 0;cnt < buckets.length;cnt++)
		{
			buckets[cnt] = new Bucket ();
		}
		
		// Task: Arrange all the given files into appropriate buckets as best as possible
		// Idea: Sort the files from largest to smallest. Then fill each bucket with the biggest files. Then, for each remaining file, add it to the smallest bucket.
		
		// Sort
		Arrays.sort(allFiles, new Comparator <File>()
		{
			@Override
			public int compare(File o1, File o2)
			{
				// Use Math.min and the maximum integer to prevent overflow when comparing big files to small files
				return (int)(Math.min(o2.length(), Integer.MAX_VALUE) - Math.min(o1.length(), Integer.MAX_VALUE));
			}
		});
		
		
		int LARGE_FILE_PASSES = 1;// 2 passes across all the buckets for the largest files.
		int largeFilePassCount = Math.min(allFiles.length, Math.min(allFiles.length, buckets.length) * LARGE_FILE_PASSES);
		
		// Populate each bucket with the largest files
		for (int cnt = 0;cnt < largeFilePassCount ;cnt++)
		{
			buckets[cnt % buckets.length].addFile(allFiles[cnt]);
		}
		
		// Now fill each bucket that's the lowest size
		for (int cnt = largeFilePassCount;cnt < allFiles.length ;cnt++)
		{
			Bucket smallestBucket = null;
		
			for (int cnt2 = 0;cnt2 < buckets.length;cnt2++)
			{
				if (smallestBucket == null || buckets[cnt2].totalSize < smallestBucket.totalSize)
				{
					smallestBucket = buckets[cnt2];
				}
			}
			
			if (smallestBucket != null)
			{
				smallestBucket.addFile(allFiles[cnt]);
			}
		}
		
		// Output the buckets into a single array list of file arrays
		ArrayList<File[]> out = new ArrayList<File[]>();		
		
		for (int cnt = 0;cnt < buckets.length;cnt++)
		{
			System.out.println ("Bucket #" + (cnt + 1) + ": count=" + buckets[cnt].files.size() + ", size=" + buckets[cnt].totalSize);
			out.add((File[])buckets[cnt].files.toArray(new File[] {}));
		}
		
		return out;
	}


	private void concealFolderIntoZip(File[] files, ZipOutputStream zos, NonCloseableOutputStream nos) throws IOException
	{
		Properties fileIndex = new Properties ();

		addExeClasses(zos, nos, updater);

		for (int cnt = 0;cnt < files.length;cnt++)
		{
			File file = files[cnt];
			
			if (file.isDirectory())
			{
				// recurse inside, through the folder
				concealFolderIntoZip (file.listFiles(), zos, nos);
			}
			else
			{
				String zipEntryPath = file.getAbsolutePath().replace(directoryToConcealPath, "");
				
				System.out.print ("Concealing " + zipEntryPath + ": ");
				
				byte[] md5Hash = null;
				
				synchronized(md)
				{
					md5Hash = md.digest(zipEntryPath.getBytes("UTF-8"));
					
					// Testing the effectiveness of the synchronized() block for MessageDigest.
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
					//	md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));md.digest(zipEntryPath.getBytes("UTF-8"));					
				}				
				
				//String md5HashBASE64 = base64_encoder.encodeBuffer(md5Hash).replaceAll("\\r\\n", "");// remove newlines
				String md5HashHex = bytesToHex (md5Hash);
				String nameHexBASE64 = bytesToHex (base64_encoder.encodeBuffer(zipEntryPath.getBytes("UTF-8")).replaceAll("\\r\\n", "").getBytes("UTF-8"));// remove newlines
				
				String key = md5HashHex;
				String value = nameHexBASE64;
				fileIndex.put(key, value);
				
				ZipEntry ze = new ZipEntry (key);//(zipEntryPath);// if we're encrypting the filename, use the hash as the key.
				zos.putNextEntry(ze);

				FileInputStream fis = new FileInputStream (file);
				BufferedInputStream bis = new BufferedInputStream (fis/*, BUFFER_SIZE*/);// We don't want a huge buffer for each file we read... default of java.io.BufferedInputStream.DEFAULT_BUFFER_SIZE (8kb) is fine.
			
				//zos.write("Hello World!".getBytes());
				
				// TODO: salt the file? would need to remove the salt when extracting the file
				//nos.write("salt".getBytes());// works fine for jpg files, need to try video files now...
				
				consumeStream (bis, nos, file.length(), updater);
				
				// Reset the updater here
				updater.reset();
				
				fis.close();
				
				zos.closeEntry();
				System.out.println ();
			}
		}
		
        // write the file index as well
		ZipEntry zep = new ZipEntry ("META-INF/index.txt");
		zos.putNextEntry(zep);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		fileIndex.store(baos, "");
		
		consumeStream (new ByteArrayInputStream (baos.toByteArray()), nos, -1, null);
        
		// Reset the updater here
		updater.reset();
		
		zos.closeEntry();

        // write the file index as well
		ZipEntry zep2 = new ZipEntry ("META-INF/index.xml");
		zos.putNextEntry(zep2);
		
		ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
		fileIndex.storeToXML (baos2, "");
		
		consumeStream (new ByteArrayInputStream (baos2.toByteArray()), nos, -1, null);
        
		// Reset the updater here
		updater.reset();
		
		zos.closeEntry();
				
	}

	// Consumes the given stream. Reads all data. Note, does not close the stream! leaving it up to the caller to close it.
	public void consumeStream (InputStream in, OutputStream out, long streamByteSize, FileTransferStatusUpdateListener progressUpdater) throws IOException
	{
		// This is super slow, especially when the buffer is large!!! byte [] buffer = new byte [1024 * 1024 * 16];
		
		// Cache the buffer instead, by output stream
		byte[] buffer = byteBuffersByOutputObjectHash.getOrDefault(out.hashCode(), new byte [BUFFER_SIZE]);
		
		// Zero out the cache
		Arrays.fill (buffer, (byte)0);
		
		// populate the cache if it doesn't exist
		if (byteBuffersByOutputObjectHash.containsKey(out.hashCode()) == false)
		{
			byteBuffersByOutputObjectHash.put(out.hashCode(), buffer);
		}
		
		// Read the response from the server, if any
		int byteCount = 0;
		int byteTotal = 0;
//		int pecentageReported = 0;// Don't report 0 progress

		// Output Stream
		while (in != null && (byteCount = in.read(buffer)) > 0)
		{
			out.write(buffer, 0, byteCount);
			byteTotal += byteCount;

			if (streamByteSize > -1 && progressUpdater != null)
			{
				int percentComplete = (int)((byteTotal / (double)streamByteSize) * 100);
				
				// Output here omitted in favour of the caller to implement the progress dialog instead
//				if ((percentComplete) % 10 == 0 && pecentageReported < percentComplete)
//				{
//					System.out.print(percentComplete + (percentComplete < 100 ? "%... " : "% Done!\n"));
//					pecentageReported = percentComplete;
//				}
				
				synchronized (progressUpdater)
				{
					progressUpdater.statusUpdate (byteTotal, streamByteSize, System.currentTimeMillis());
				}
			}
		}
		out.close();
	}
	
	private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
	static class NonCloseableOutputStream extends java.io.FilterOutputStream {
	    public NonCloseableOutputStream(OutputStream out) {
	        super(out);
	    }

	    @Override public void close() throws IOException {
	        flush();
	    }
	}
	
	static interface FileTransferStatusUpdateListener
	{
		void statusUpdate(int byteTotal, long fileSize, long currentTimeMillis);

		void reset();
	}
}
