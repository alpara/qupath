/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.imagej.helpers;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;
import qupath.imagej.images.servers.ImagePlusServer;
import qupath.imagej.images.servers.ImagePlusServerBuilder;
import qupath.imagej.objects.PathImagePlus;
import qupath.imagej.objects.ROIConverterIJ;
import qupath.lib.awt.color.ColorToolsAwt;
import qupath.lib.awt.common.AwtTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

/**
 * Collection of static methods to help with using ImageJ with QuPath.
 * 
 * @author Pete Bankhead
 *
 */
public class IJTools {
	
	final private static Logger logger = LoggerFactory.getLogger(IJTools.class);
	
	// Defines what fraction of total available memory can be allocated to transferring a single image to ImageJ 
	private static double MEMORY_THRESHOLD = 0.5;

	/**
	 * @param threshold - value in the interval ]0;1] defining the maximum remaining memory fraction an image can have 
	 * when importing an image to ImageJ
	 */
	public static void setMemoryThreshold(double threshold) {
		
		// Just make sure the user entered something that makes sense
		double new_threshold = ( threshold > 1 ) ? 1   : threshold;
		new_threshold        = ( threshold < 0 ) ? 0.1 : new_threshold;

		MEMORY_THRESHOLD = new_threshold;
	}
	
	/**
	 * Check if sufficient memory is available to request pixels for a specific region, and the number 
	 * of pixels is less than the maximum length of a Java array.
	 * 
	 * @param region - the requested region coming from 
	 * @param imageData - this BufferedImage
	 * @return - true if the memory is sufficient
	 * @throws Exception - either the fact that IamgeJ cannot handle the image size or that the memory is insufficient
	 */
	public static boolean isMemorySufficient(RegionRequest region, final ImageData<BufferedImage> imageData) throws Exception {
		
		// Gather data on the image
		ImageServer<BufferedImage> server = imageData.getServer();
		int bytesPerPixel = (server.isRGB()) ? 4 : server.getBitsPerPixel() * server.nChannels() / 8;
		
		// Gather data on the region being requested
		double regionWidth = region.getWidth() / region.getDownsample();
		double regionHeight = region.getHeight() / region.getDownsample();
		
		double approxPixelCount = regionWidth * regionHeight;

		// Kindly request garbage collection
		Runtime.getRuntime().gc();
		
		// Compute (very simply) how much memory this image could take
		long approxMemory = (long) approxPixelCount * bytesPerPixel;
		
		// Compute the available memory, as per https://stackoverflow.com/a/18366283
		long allocatedMemory      = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
		long presumableFreeMemory = Runtime.getRuntime().maxMemory() - allocatedMemory;
		
		// Prepare pretty formatting if needed
		DecimalFormat df = new DecimalFormat("00.00");
		
		if (approxPixelCount > 2147480000L) 
			throw 
				new Exception("ImageJ cannot handle images this big ("+
							   (int) regionWidth+"x"+ (int)regionHeight+" pixels).\n"+
							   "Try again with a smaller region, or a higher downsample factor.");
		
		if (approxMemory > presumableFreeMemory * MEMORY_THRESHOLD) 
			throw 
				new Exception("There is not enough free memory to open this region in ImageJ\n"+
						   "Image memory requirement: "+ df.format(approxMemory/(1024*1024))+"MB\n"+
						   "Available Memory: "+df.format(presumableFreeMemory/(1024*1024) * MEMORY_THRESHOLD)+"MB\n\n"+
						   "Try again with a smaller region, or a higher downsample factor,"+
						   "or modify the memory threshold using IJTools.setMemoryThreshold(double threshold)");
		
		return (approxPixelCount > 2147480000L || approxMemory > presumableFreeMemory * MEMORY_THRESHOLD);
	}


	/**
	 * Extract a full ImageJ hyperstack for a specific region, using all z-slices and time points.
	 * 
	 * @param server
	 * @param request
	 * @return
	 * @throws IOException
	 */
	public static ImagePlus extractHyperstack(ImageServer<BufferedImage> server, RegionRequest request) throws IOException {
		return extractHyperstack(server, request, 0, server.nZSlices(), 0, server.nTimepoints());
	}
	
	/**
	 * Extract a full ImageJ hyperstack for a specific region, for specified ranges of z-slices and time points.
	 * 
	 * @param server
	 * @param request
	 * @param zStart
	 * @param zEnd
	 * @param tStart
	 * @param tEnd
	 * @return
	 * @throws IOException
	 */
	public static ImagePlus extractHyperstack(ImageServer<BufferedImage> server, RegionRequest request, int zStart, int zEnd, int tStart, int tEnd) throws IOException {
		
		ImagePlusServer serverIJ = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(server);
		
		int nChannels = -1;
		int nZ = zEnd - zStart;
		int nT = tEnd - tStart;
		double downsample = request.getDownsample();
		ImageStack stack = null;
		Calibration cal = null;
		for (int t = tStart; t < tEnd; t++) {
			for (int z = zStart; z < zEnd; z++) {
				RegionRequest request2 = RegionRequest.createInstance(server.getPath(), downsample,
			            request.getX(), request.getY(), request.getWidth(), request.getHeight(),
			            z, t
			    );
			    ImagePlus imp = serverIJ.readImagePlusRegion(request2).getImage();
			    if (stack == null) {
			    	stack = new ImageStack(imp.getWidth(), imp.getHeight());
			    }
			    // Append to original image stack
			    for (int i = 1; i <= imp.getStack().getSize(); i++) {
			        stack.addSlice(imp.getStack().getProcessor(i));
			    }
			    // Get the last calibration
		    	cal = imp.getCalibration();
		    	nChannels = imp.getNChannels();
			}
		}
	    if (cal != null && !Double.isNaN(server.getZSpacingMicrons())) {
	        cal.pixelDepth = server.getZSpacingMicrons();
	        cal.setZUnit("um");
	    }
	    
	    ImagePlus imp = new ImagePlus(server.getDisplayedImageName(), stack);
	    CompositeImage impComp = null;
	    if (imp.getType() != ImagePlus.COLOR_RGB && nChannels > 1) {
		    impComp = new CompositeImage(imp, CompositeImage.COMPOSITE);
		    imp = impComp;
	    }
	    imp.setCalibration(cal);
	    imp.setDimensions(nChannels, nZ, nT);
	    // Set colors, if necesssary
	    if (impComp != null) {
		    for (int c = 0; c < nChannels; c++) {
		    	impComp.setChannelLut(
		    			LUT.createLutFromColor(
		    					ColorToolsAwt.getCachedColor(
		    							server.getDefaultChannelColor(c))), c+1);
		    }
	    }
	    return imp;
	}
	

	/**
	 * Set the name of an image based on a PathObject.
	 * <p>
	 * Useful whenever the ROI for an object is being extracted for display separately.
	 * 
	 * @param pathImage
	 * @param pathObject
	 */
	public static void setTitleFromObject(PathImage<ImagePlus> pathImage, PathObject pathObject) {
		if (pathImage != null && pathObject instanceof TMACoreObject)
			pathImage.getImage().setTitle(pathObject.getDisplayedName());
	}

	/**
	 * Set an ImagePlus's Calibration and FileInfo properties based on a RegionRequest and PathImageServer.
	 * It is assumed at the image contained in the ImagePlus has been correctly read from the server.
	 * 
	 * @param imp
	 * @param request
	 * @param server
	 */
	public static void calibrateImagePlus(final ImagePlus imp, final RegionRequest request, final ImageServer<BufferedImage> server) {
		// Set the file info & calibration appropriately		
		// TODO: Check if this is correct!  And consider storing more info regarding actual server...
		Calibration cal = new Calibration();
		double downsampleFactor = request.getDownsample();
	
		double pixelWidth = server.getPixelWidthMicrons();
		double pixelHeight = server.getPixelHeightMicrons();
		if (!Double.isNaN(pixelWidth + pixelHeight)) {
			cal.pixelWidth = pixelWidth * downsampleFactor;
			cal.pixelHeight = pixelHeight * downsampleFactor;
			cal.pixelDepth = server.getZSpacingMicrons();
			if (server.nTimepoints() > 1) {
				cal.frameInterval = server.getTimePoint(1);
				if (server.getTimeUnit() != null)
					cal.setTimeUnit(server.getTimeUnit().toString());
			}
			cal.setUnit("um");
		}
	
		// Set the origin
		cal.xOrigin = -request.getX() / downsampleFactor;
		cal.yOrigin = -request.getY() / downsampleFactor;
	
		// Need to set calibration afterwards, as it will be copied
		imp.setCalibration(cal);
	
	
		FileInfo fi = imp.getFileInfo();
		File file = new File(server.getPath());
		fi.directory = file.getParent() + File.separator;
		fi.fileName = file.getName();
		String path = server.getPath();
		if (path != null && path.toLowerCase().startsWith("http"))
			fi.url = path;
		imp.setFileInfo(fi);
	
		imp.setProperty("Info", "location="+server.getPath());
	}

	/**
	 * Estimate the downsample factor for an image region extracted from an image server, based upon 
	 * the ratio of pixel sizes if possible or ratio of dimensions if necessary.
	 * <p>
	 * Note that the ratio of dimensions is only suitable if the full image has been extracted!
	 * 
	 * @param imp
	 * @param server
	 * @return
	 */
	public static double estimateDownsampleFactor(final ImagePlus imp, final ImageServer<BufferedImage> server) {
		// Try to get the downsample factor from pixel size;
		// if that doesn't work, resort to trying to get it from the image dimensions
		double downsampleFactor;
		if (server.hasPixelSizeMicrons())
			downsampleFactor = imp.getCalibration().pixelWidth / server.getPixelWidthMicrons();
		//			downsampleFactor = server.getPixelWidthMicrons() / imp.getCalibration().pixelWidth;
		else {
			double downsampleX = (double)server.getWidth() / imp.getWidth();
			double downsampleY = (double)server.getHeight() / imp.getHeight();
			if (GeneralTools.almostTheSame(downsampleX, downsampleY, 0.001))
				logger.warn("ImageJ downsample factor is being estimated from image dimensions - assumes that ImagePlus corresponds to the full image server");
			else
				logger.warn("ImageJ downsample factor is being estimated from image dimensions - assumes that ImagePlus corresponds to the full image server (and these don't seem to match! {} and {})", downsampleX, downsampleY);
			downsampleFactor = (downsampleX + downsampleY) / 2.0;
		}
		return downsampleFactor;
	}

	/**
	 * Create a QuPath annotation or detection object for a specific ImageJ Roi.
	 * 
	 * @param imp
	 * @param server
	 * @param roi
	 * @param downsampleFactor
	 * @param makeDetection
	 * @param c
	 * @param z
	 * @param t
	 * @return
	 */
	public static PathObject convertToPathObject(ImagePlus imp, ImageServer<?> server, Roi roi, double downsampleFactor, boolean makeDetection, int c, int z, int t) {
		Calibration cal = imp.getCalibration();
		ROI pathROI = ROIConverterIJ.convertToPathROI(roi, cal, downsampleFactor, c, z, t);
		if (pathROI == null)
			return null;
		PathObject pathObject;
		if (makeDetection && !(pathROI instanceof PointsROI))
			pathObject = PathObjects.createDetectionObject(pathROI);
		else
			pathObject = PathObjects.createAnnotationObject(pathROI);
		Color color = roi.getStrokeColor();
		if (color == null)
			color = Roi.getColor();
		pathObject.setColorRGB(color.getRGB());
		if (roi.getName() != null)
			pathObject.setName(roi.getName());
		return pathObject;
	}
	
	
	/**
	 * Show an ImageProcessor (or array of similar ImageProcessors as a stack).
	 * This is really intended for use with debugging... it takes care of creating an ImagePlus
	 * with the specified title, reseting brightness/contrast suitably, setting a roi (if required)
	 * and showing the result.
	 * 
	 * @param name
	 * @param roi
	 * @param ips
	 */
	public static void quickShowImage(final String name, final Roi roi, final ImageProcessor... ips) {
		ImageStack stack = null;
		for (ImageProcessor ip : ips) {
			if (!(ip instanceof ColorProcessor))
				ip.resetMinAndMax();
			if (stack == null)
				stack = new ImageStack(ip.getWidth(), ip.getHeight());
			stack.addSlice(ip);
		}
		ImagePlus imp = new ImagePlus(name, stack);
		if (roi != null)
			imp.setRoi(roi);
		if (SwingUtilities.isEventDispatchThread())
			imp.show();
		else
			SwingUtilities.invokeLater(() -> imp.show());
	}

	/**
	 * Show an ImageProcessor (or array of similar ImageProcessors as a stack).
	 * This is really intended for use with debugging... it takes care of creating an ImagePlus
	 * with the specified title, reseting brightness/contrast suitably and showing the result.
	 * 
	 * @param name
	 * @param ips
	 */
	public static void quickShowImage(final String name, final ImageProcessor... ips) {
		quickShowImage(name, null, ips);
	}
	

}
