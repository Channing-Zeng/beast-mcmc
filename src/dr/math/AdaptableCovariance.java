package dr.math;

import dr.math.matrixAlgebra.ReadableMatrix;
import dr.math.matrixAlgebra.ReadableVector;
import dr.math.matrixAlgebra.WrappedMatrix;

/**
 * @author Marc A. Suchard
 * @author Guy Baele
 * @author Xiang Ji
 */
public class AdaptableCovariance {

    final private int dim;
    final private double[][] empirical;
    final private double[] oldMeans;
    final private double[] newMeans;

    private int updates;

    public AdaptableCovariance(int dim) {
        this.dim = dim;
        this.empirical = new double[dim][dim];
        this.oldMeans = new double[dim];
        this.newMeans = new double[dim];

        updates = 0;
    }

    public int getUpdateCount() { return updates; }

    public void update(ReadableVector x) {

        assert (x.getDim() == dim);

        if (shouldUpdate()) {

            ++updates;

            updateMean(x);

            if (updates > 1) {
                updateVariance(x);
            }
        }
    }

    public ReadableMatrix getCovariance() {
        return new WrappedMatrix.ArrayOfArray(empirical);
    }

    protected boolean shouldUpdate() { return true; }

    private void updateMean(ReadableVector x) {
        for (int i = 0; i < dim; i++) {
            newMeans[i] = ((oldMeans[i] * (updates - 1)) + x.get(i)) / updates;
        }
    }

    private void updateVariance(ReadableVector x) {
        for (int i = 0; i < dim; i++) {
            for (int j = i; j < dim; j++) {
                empirical[i][j] = calculateCovariance(empirical[i][j], x, i, j);
                empirical[j][i] = empirical[i][j];
            }
        }
    }

    private double calculateCovariance(double currentMatrixEntry, ReadableVector values, int firstIndex, int secondIndex) {

        double result = currentMatrixEntry * (updates - 2);
        result += (values.get(firstIndex) * values.get(secondIndex));
        result += ((updates - 1) * oldMeans[firstIndex] * oldMeans[secondIndex] - updates * newMeans[firstIndex] * newMeans[secondIndex]);
        result /= ((double)(updates - 1));

        return result;
    }

    public class WithSubsampling extends AdaptableCovariance {

        public WithSubsampling(int dim) {
            super(dim);
        }

        @Override
        protected boolean shouldUpdate() {
             // TODO Add logic in subclass to control how often updates are made
             return true;
         }

    }
}