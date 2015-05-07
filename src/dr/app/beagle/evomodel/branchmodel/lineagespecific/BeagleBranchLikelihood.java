package dr.app.beagle.evomodel.branchmodel.lineagespecific;

import java.util.ArrayList;
import java.util.List;

import beagle.Beagle;
import beagle.BeagleFactory;
import dr.app.beagle.evomodel.branchmodel.BranchModel;
import dr.app.beagle.evomodel.branchmodel.HomogeneousBranchModel;
import dr.app.beagle.evomodel.sitemodel.GammaSiteRateModel;
import dr.app.beagle.evomodel.sitemodel.SiteRateModel;
import dr.app.beagle.evomodel.substmodel.FrequencyModel;
import dr.app.beagle.evomodel.substmodel.HKY;
import dr.app.beagle.evomodel.substmodel.SubstitutionModel;
import dr.app.beagle.evomodel.treelikelihood.BeagleTreeLikelihood;
import dr.app.beagle.evomodel.treelikelihood.BufferIndexHelper;
import dr.app.beagle.evomodel.treelikelihood.PartialsRescalingScheme;
import dr.app.beagle.evomodel.treelikelihood.SubstitutionModelDelegate;
import dr.app.beagle.tools.BeagleSequenceSimulator;
import dr.app.beagle.tools.Partition;
import dr.evolution.alignment.Alignment;
import dr.evolution.alignment.PatternList;
import dr.evolution.datatype.DataType;
import dr.evolution.datatype.Nucleotides;
import dr.evolution.io.NewickImporter;
import dr.evolution.tree.NodeRef;
import dr.evolution.tree.Tree;
import dr.evomodel.branchratemodel.BranchRateModel;
import dr.evomodel.branchratemodel.StrictClockBranchRates;
import dr.evomodel.tree.TreeModel;
import dr.inference.loggers.LogColumn;
import dr.inference.loggers.NumberColumn;
import dr.inference.model.Likelihood;
import dr.inference.model.Model;
import dr.inference.model.Parameter;
import dr.math.MathUtils;

@SuppressWarnings("serial")
public class BeagleBranchLikelihood implements Likelihood {

	private static final boolean DEBUG = true;
	
	// Constructor fields
	private PatternList patternList;
	private TreeModel treeModel;
	private BranchModel branchModel;
	private SiteRateModel siteRateModel;
	private FrequencyModel freqModel;
	private BranchRateModel branchRateModel;

	// Likelihood fields
	private String id = null;
	private boolean used = true;

	// Beagle fields
	private Beagle beagle;
//	private BufferIndexHelper matrixBufferHelper;
	private BufferIndexHelper partialBufferHelper;
	private SubstitutionModelDelegate substitutionModelDelegate;

//	private int[] operationCount;
//	private int[][] operations;

	public BeagleBranchLikelihood(PatternList patternList, //
			TreeModel treeModel, //
			BranchModel branchModel, //
			SiteRateModel siteRateModel, FrequencyModel freqModel, //
			BranchRateModel branchRateModel //
	) {

		this.patternList = patternList;
		this.treeModel = treeModel;
		this.branchModel = branchModel;
		this.siteRateModel = siteRateModel;
		this.freqModel = freqModel;
		this.branchRateModel = branchRateModel;

		this.loadBeagleInstance();

	}// END: Constructor

	public double getBranchLogLikelihood(int branchIndex) {

		// gamma category rates
		double[] categoryRates = siteRateModel.getCategoryRates();
		beagle.setCategoryRates(categoryRates);

		double[] categoryWeights = this.siteRateModel.getCategoryProportions();
		beagle.setCategoryWeights(0, categoryWeights);

		double[] frequencies = substitutionModelDelegate
				.getRootStateFrequencies();
		beagle.setStateFrequencies(0, frequencies);

		substitutionModelDelegate.updateSubstitutionModels(beagle);

		int count = 1;
		double[] loglikelihood = new double[count];

		int nodeNum = branchIndex;
		NodeRef node = treeModel.getNode(nodeNum);

		// traverse down that node populating buffers and calculating partials
		traverse(treeModel, node);

		int parentNum = treeModel.getParent(node).getNumber();

		beagle.calculateEdgeLogLikelihoods(new int[] { parentNum }, // int[]
																	// parentBufferIndices
				new int[] { nodeNum }, // int[] childBufferIndices
				new int[] { 0 }, // int[] probabilityIndices
				null, // firstDerivativeIndices
				null, // secondDerivativeIndices
				new int[] { 0 }, // int[] categoryWeightsIndices
				new int[] { 0 }, // int[] stateFrequenciesIndices
				new int[] { Beagle.NONE }, // cumulativeScaleIndices
				1, // count
				loglikelihood, //
				null, // outSumFirstDerivative, //
				null // outSumSecondDerivative //
		);

		return loglikelihood[0];
	}// END: getLogLikelihood

	private boolean traverse(TreeModel treeModel, NodeRef node) {

		boolean update = false;
		int nodeNum = node.getNumber();
		NodeRef parentNode = treeModel.getParent(node);

		// populate buffer
		if (parentNode != null) {

			double branchRate = branchRateModel.getBranchRate(treeModel, node);
			double parentHeight = treeModel.getNodeHeight(parentNode);
			double nodeHeight = treeModel.getNodeHeight(node);
			double branchLength = branchRate * (parentHeight - nodeHeight);

			substitutionModelDelegate.flipMatrixBuffer(nodeNum);
			
			substitutionModelDelegate.updateTransitionMatrices(//
					beagle, //
					new int[] { nodeNum }, //
					new double[] { branchLength }, //
					1 //
					);

			if(DEBUG){
			System.out.println("At branch " + nodeNum);
			System.out.println(" Length " + branchLength + ": node " + nodeNum
					+ ", height=" + nodeHeight + " parent " + parentNode);
			System.out.println(" Populating transition matrix buffer");
			}//END: DEBUG check
			
		}// END: parent check

		// update the partial likelihoods
		if (!treeModel.isExternal(node)) {

			// Traverse down the two child nodes
			NodeRef child1 = treeModel.getChild(node, 0);
			boolean update1 = traverse(treeModel, child1);

			NodeRef child2 = treeModel.getChild(node, 1);
			boolean update2 = traverse(treeModel, child2);

			// If either child node was updated then update this node too
			if (update1 || update2) {

				int[] operations = new int[Beagle.OPERATION_TUPLE_SIZE];

				partialBufferHelper.flipOffset(nodeNum);

				// destinationPartials
				operations[Beagle.OPERATION_TUPLE_SIZE - 7] = partialBufferHelper
						.getOffsetIndex(nodeNum);
				// destinationScaleWrite
				operations[Beagle.OPERATION_TUPLE_SIZE - 6] = Beagle.NONE;
				// destinationScaleRead
				operations[Beagle.OPERATION_TUPLE_SIZE - 5] = Beagle.NONE;
				// source node 1
				operations[Beagle.OPERATION_TUPLE_SIZE - 4] = partialBufferHelper
						.getOffsetIndex(child1.getNumber());
				// source matrix 1
				operations[Beagle.OPERATION_TUPLE_SIZE - 3] = substitutionModelDelegate
						.getMatrixIndex(child1.getNumber());
				// source node 2
				operations[Beagle.OPERATION_TUPLE_SIZE - 2] = partialBufferHelper
						.getOffsetIndex(child2.getNumber());
				// source matrix 2
				operations[Beagle.OPERATION_TUPLE_SIZE - 1] = substitutionModelDelegate
						.getMatrixIndex(child2.getNumber());

				beagle.updatePartials(operations, 3, Beagle.NONE);

				if(DEBUG){
				System.out.println(" Child nodes updated");
				System.out.println(" Populating partial buffer");
				}//END: DEBUG check
				
				update = true;

			}// END: children updated check

		} else {

			String id = treeModel.getTaxonId(nodeNum);
			int sequenceIndex = patternList.getTaxonIndex(id);

			int patternCount = patternList.getPatternCount();
			int[] states = new int[patternCount];
			for (int j = 0; j < patternCount; j++) {

				states[j] = patternList.getPatternState(sequenceIndex, j);

			}

			beagle.setTipStates(nodeNum, states);

			if(DEBUG){
			System.out.println(" External branch " + id);
			System.out.println(" Populating tip states buffer");
		}//END: DEBUG check
			
			// update = false;
		}// END: external branch check

		return update;
	}// END: traverse

	// //////////////
	// ---PUBLIC---//
	// //////////////

	@Override
	public double getLogLikelihood() {
		double loglikelihood = 0;

		// TODO

		return loglikelihood;
	}// END: getLogLikelihood

	// ///////////////
	// ---PRIVATE---//
	// ///////////////

	public void finalizeBeagle() throws Throwable {
		beagle.finalize();
	}// END: finalizeBeagle

	private void loadBeagleInstance() {

		this.substitutionModelDelegate = new SubstitutionModelDelegate(
				treeModel, branchModel);

		DataType dataType = freqModel.getDataType();

		int partitionSiteCount = patternList.getPatternCount();

		int nodeCount = treeModel.getNodeCount();
//		this.matrixBufferHelper = new BufferIndexHelper(nodeCount, 0);

		int tipCount = treeModel.getExternalNodeCount();
		int internalNodeCount = treeModel.getInternalNodeCount();

		partialBufferHelper = new BufferIndexHelper(nodeCount, tipCount);
		BufferIndexHelper scaleBufferHelper = new BufferIndexHelper(
				internalNodeCount + 1, 0);

		int compactPartialsCount = tipCount;
		int stateCount = dataType.getStateCount();
		int patternCount = partitionSiteCount;
		int siteRateCategoryCount = siteRateModel.getCategoryCount();

		int[] resourceList = new int[] { 0 };
		long preferenceFlags = 0;
		long requirementFlags = 0;

		beagle = BeagleFactory.loadBeagleInstance(tipCount, //
				partialBufferHelper.getBufferCount(), //
				compactPartialsCount, //
				stateCount, //
				patternCount, //
				substitutionModelDelegate.getEigenBufferCount(), //
				substitutionModelDelegate.getMatrixBufferCount(), //
				siteRateCategoryCount, //
				scaleBufferHelper.getBufferCount(), //
				resourceList, //
				preferenceFlags, //
				requirementFlags//
				);

	}// END: loadBeagleInstance

	// /////////////////
	// ---INHERITED---//
	// /////////////////

	@Override
	public LogColumn[] getColumns() {
		return new dr.inference.loggers.LogColumn[] { new LikelihoodColumn(
				getId() == null ? "likelihood" : getId()) };
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public Model getModel() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void makeDirty() {
		// TODO Auto-generated method stub

	}

	@Override
	public String prettyName() {
		return Abstract.getPrettyName(this);
	}

	@Override
	public boolean isUsed() {
		return used;
	}

	@Override
	public void setUsed() {
		used = true;
	}

	@Override
	public boolean evaluateEarly() {
		return false;
	}

	// ///////////////////////
	// ---PRIVATE CLASSES---//
	// ///////////////////////

	private class LikelihoodColumn extends NumberColumn {

		public LikelihoodColumn(String label) {
			super(label);
		}// END: Constructor

		public double getDoubleValue() {
			return getLogLikelihood();
		}

	}// END: LikelihoodColumn class

	// ////////////
	// ---TEST---//
	// ////////////

	public static void main(String[] args) {

		try {

			MathUtils.setSeed(666);

			int sequenceLength = 1000;
			ArrayList<Partition> partitionsList = new ArrayList<Partition>();

			// create tree
			NewickImporter importer = new NewickImporter(
					"((SimSeq1:22.0,SimSeq2:22.0):12.0,(SimSeq3:23.1,SimSeq4:23.1):10.899999999999999);");
			Tree tree = importer.importTree(null);
			TreeModel treeModel = new TreeModel(tree);

			// create Frequency Model
			Parameter freqs = new Parameter.Default(new double[] { 0.25, 0.25,
					0.25, 0.25 });
			FrequencyModel freqModel = new FrequencyModel(Nucleotides.INSTANCE,
					freqs);

			// create branch model
			Parameter kappa1 = new Parameter.Default(1, 1);

			HKY hky1 = new HKY(kappa1, freqModel);

			BranchModel homogeneousBranchModel = new HomogeneousBranchModel(
					hky1);

			List<SubstitutionModel> substitutionModels = new ArrayList<SubstitutionModel>();
			substitutionModels.add(hky1);
			List<FrequencyModel> freqModels = new ArrayList<FrequencyModel>();
			freqModels.add(freqModel);

			// create branch rate model
			Parameter rate = new Parameter.Default(1, 1.000);
			BranchRateModel branchRateModel = new StrictClockBranchRates(rate);

			// create site model
			GammaSiteRateModel siteRateModel = new GammaSiteRateModel(
					"siteModel");

			// create partition
			Partition partition1 = new Partition(treeModel, //
					homogeneousBranchModel,//
					siteRateModel, //
					branchRateModel, //
					freqModel, //
					0, // from
					sequenceLength - 1, // to
					1 // every
			);

			partitionsList.add(partition1);

			// feed to sequence simulator and generate data
			BeagleSequenceSimulator simulator = new BeagleSequenceSimulator(
					partitionsList);

			Alignment alignment = simulator.simulate(false, false);

			BeagleTreeLikelihood btl = new BeagleTreeLikelihood(alignment,
					treeModel, homogeneousBranchModel, siteRateModel,
					branchRateModel, null, false,
					PartialsRescalingScheme.DEFAULT);

			System.out.println("BTL(homogeneous) = " + btl.getLogLikelihood());

			BeagleBranchLikelihood bbl = new BeagleBranchLikelihood(alignment,
					treeModel, homogeneousBranchModel, siteRateModel,
					freqModel, branchRateModel);

			// bbl.testLogLikelihood();

			int branchIndex = 4;
			System.out.println(bbl.getBranchLogLikelihood(branchIndex));

			bbl.finalizeBeagle();

		} catch (Exception e) {

			e.printStackTrace();
			System.exit(-1);

		} catch (Throwable e) {

			e.printStackTrace();
			System.exit(-1);

		}// END: try-catch block

	}// END: main

}// END: class
