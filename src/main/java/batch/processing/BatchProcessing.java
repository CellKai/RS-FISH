package batch.processing;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import compute.RadialSymmetry;
import fit.Spot;
import gui.interactive.HelperFunctions;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.Opener;
import imglib2.RealTypeNormalization;
import imglib2.TypeTransformingRandomAccessibleInterval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.algorithm.localization.EllipticGaussianOrtho;
import net.imglib2.algorithm.localization.LevenbergMarquardtSolver;
import net.imglib2.algorithm.localization.MLEllipticGaussianEstimator;
import net.imglib2.algorithm.localization.PeakFitter;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import parameters.GUIParams;
import parameters.RadialSymmetryParameters;
import util.ImgLib2Util;
import util.opencsv.CSVWriter;

public class BatchProcessing {
	/*
	 * Class to process multiple images in a batch mode
	 * */

	public static void startBatchProcessing(GUIParams params, String path) {
		File folder = new File(path);
		File[] listOfFiles = folder.listFiles();

		for (File file : listOfFiles) {
			if (file.isFile()) {
				System.out.println(file.getName());
			
				ImagePlus imp = new Opener().openImage(file.getAbsolutePath());
				
				// make some dirty code as it is not defined at compile time, but
				// for all subsequent code it is
				double[] minmax = HelperFunctions.computeMinMax((Img) ImageJFunctions.wrapReal(imp));

				float min = (float) minmax[0];
				float max = (float) minmax[1];

				// set the calibration for the given image
				double[] calibration = HelperFunctions.initCalibration(imp, imp.getNDimensions()); 
				// set the parameters for the radial symmetry 
				RadialSymmetryParameters rsm = new RadialSymmetryParameters(params, calibration);
				// normalize the image if necessary
				RandomAccessibleInterval<FloatType> rai;
				if (!Double.isNaN(min) && !Double.isNaN(max)) // if normalizable
					rai = new TypeTransformingRandomAccessibleInterval<>(ImageJFunctions.wrap(imp),
							new RealTypeNormalization<>(min, max - min), new FloatType());
				else // otherwise use
					rai = ImageJFunctions.wrap(imp);

				// x y z
				long[] dim = new long[imp.getNDimensions()];

				for(int j = 0; j < dim.length; ++j)
					dim[j] = imp.getDimensions()[j];

				// stores the intensity values for gauss fitting
				ArrayList<Float> intensity = new ArrayList<>(0);

				boolean useGaussFit = false;
				ArrayList<Spot> spots = processImage(imp, rai, rsm, dim, useGaussFit, params.getSigmaDoG(), intensity);
				
				saveResult(path + "/csv/", file.getName(), spots, intensity);
				
			}
		}		
	}


	public static ArrayList<Spot> processImage(ImagePlus imp, RandomAccessibleInterval<FloatType> rai, RadialSymmetryParameters rsm,
			long[] dim, boolean gaussFit, double sigma, ArrayList<Float> intensity) {

		int numDimensions = dim.length;

		RadialSymmetry rs = new RadialSymmetry(rsm, rai);

		// TODO: Check if this part is redundant 
		// TODO: if the detect spot has at least 1 inlier add it
		ArrayList<Spot> filteredSpots = HelperFunctions.filterSpots(rs.getSpots(), 1 );

		// user wants to have the gauss fit here
		if (gaussFit) {
			double [] typicalSigmas = new double[numDimensions];
			for (int d = 0; d < numDimensions; d++)
				typicalSigmas[d] = sigma;

			PeakFitter<FloatType> pf = new PeakFitter<FloatType>(ImageJFunctions.wrap(imp), (ArrayList)filteredSpots,
					new LevenbergMarquardtSolver(), new EllipticGaussianOrtho(), 
					new MLEllipticGaussianEstimator(typicalSigmas)); // use a non-symmetric gauss (sigma_x, sigma_y, sigma_z or sigma_xy & sigma_z)
			pf.process();

			// TODO: make spot implement Localizable - then this is already a HashMap that maps Spot > double[]
			// this is actually a Map< Spot, double[] >
			final Map< Localizable, double[] > fits = pf.getResult();

			// FIXME: is the order consistent
			for ( final Spot spot : filteredSpots )
			{
				double[] params = fits.get( spot );
				intensity.add(new Float(params[numDimensions]));
			}
		}
		else{
			//  iterate over all points and perform the linear interpolation for each of the spots

			NLinearInterpolatorFactory<FloatType> factory = new NLinearInterpolatorFactory<>();
			RealRandomAccessible<FloatType> interpolant = Views.interpolate(Views.extendMirrorSingle( ImageJFunctions.wrapFloat(imp)), factory);

			for (Spot fSpot : filteredSpots){
				RealRandomAccess<FloatType> rra = interpolant.realRandomAccess();
				double[] position = fSpot.getCenter();
				rra.setPosition(position);
				intensity.add(new Float(rra.get().get()));	
			}
		}
		return filteredSpots;
	}

	// returns only xyz stack from the ImagePlus object
	public static ImagePlus getXyz(ImagePlus imp){
		final ImageStack stack = new ImageStack(imp.getWidth(), imp.getHeight() );
		int [] impDimensions = imp.getDimensions();

		for (int z = 0; z < impDimensions[3]; z++){
			int id = imp.getStackIndex(1, z + 1, 1);
			stack.addSlice(imp.getStack().getProcessor( id ));
		}

		ImagePlus xyzImp = new ImagePlus("merge", stack );
		// xyzImp.setDimensions( 1, impDimensions[3], 1 );

		return xyzImp;
	}

	public static void saveResult(String path, String fileName, ArrayList<Spot> spots, ArrayList<Float> intensity) {
		CSVWriter writer = null;
		String[] nextLine = new String [5];

		try {
			writer = new CSVWriter(new FileWriter(path + "/" + fileName + ".csv"), '\t', CSVWriter.NO_QUOTE_CHARACTER);
			for (int j = 0; j < spots.size(); j++) {
				double[] position = spots.get(j).getCenter();
			
				nextLine = new String[]{
						String.valueOf(j + 1), 
						String.format(java.util.Locale.US, "%.2f", position[0]), 
						String.format(java.util.Locale.US, "%.2f", position[1]), 
						String.format(java.util.Locale.US, "%.2f", position[2]),
						String.format(java.util.Locale.US, "%.2f", intensity.get(j))
						}; 	
				writer.writeNext(nextLine);
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		new ImageJ();

		boolean useRANSAC = true;
		final GUIParams params = new GUIParams(useRANSAC);
		params.setAnisotropyCoefficient(1.0f);
		// pre-detection
		params.setSigmaDog(1.5f);
		params.setThresholdDoG(0.0033f);
		// detection
		params.setSupportRadius(3);
		params.setInlierRatio(0.37f);
		params.setMaxError(0.50f);

		String path = "/media/milkyklim/Samsung_T3/2017-08-24-intronic-probes/N2_dpy-23_ex_int_ama-1_015/channels/c3";		
		startBatchProcessing(params, path);

		System.out.println("DOGE! ");
		
	}

}
