package edu.ucsf.rbvi.clusterMaker2.internal.algorithms.matrix;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.cytoscape.application.CyUserLog;
import org.apache.log4j.Logger;

import cern.colt.function.tdouble.IntIntDoubleFunction;
import cern.colt.function.tdouble.DoubleFunction;
import cern.jet.math.tdouble.DoubleFunctions;
import cern.jet.random.tdouble.Binomial;
import cern.jet.random.tdouble.Normal;
import cern.jet.random.tdouble.engine.DoubleRandomEngine;
import cern.colt.matrix.tdouble.DoubleMatrix1D;
import cern.colt.matrix.tdouble.DoubleFactory1D;
import cern.colt.matrix.tdouble.DoubleFactory2D;
import cern.colt.matrix.tdouble.DoubleMatrix2D;
import cern.colt.matrix.tdouble.algo.DenseDoubleAlgebra;
import cern.colt.matrix.tdouble.algo.DoubleStatistic;

import edu.ucsf.rbvi.clusterMaker2.internal.api.DistanceMetric;
import edu.ucsf.rbvi.clusterMaker2.internal.api.Matrix;
import edu.ucsf.rbvi.clusterMaker2.internal.api.Matrix.DISTRIBUTION;
import edu.ucsf.rbvi.clusterMaker2.internal.api.MatrixOps;

public class ColtMatrix implements Matrix {
	protected DoubleMatrix2D data;
	protected int[] index = null;
	protected int nRows;
	protected int nColumns;
	protected String[] rowLabels = null;
	protected String[] columnLabels = null;
	protected double maxValue = Double.MIN_VALUE;
	protected double minValue = Double.MAX_VALUE;
	protected boolean symmetric = false;
	protected boolean transposed = false;
	private int nThreads = -1;
	final Logger logger = Logger.getLogger(CyUserLog.NAME);

	// For debugging messages
	private static DecimalFormat scFormat = new DecimalFormat("0.###E0");
	private static DecimalFormat format = new DecimalFormat("0.###");

	// Our MatrixOps
	public final ColtOps ops;

	public ColtMatrix() {
		nThreads = Runtime.getRuntime().availableProcessors()-1;
		ops = new ColtOps(this);
	}

	public ColtMatrix(ColtMatrix mat) {
		this();
		data = mat.data.copy();
		nRows = data.rows();
		nColumns = data.columns();
		transposed = mat.transposed;
		symmetric = mat.symmetric;
		minValue = mat.minValue;
		maxValue = mat.maxValue;
		if (mat.rowLabels != null)
			rowLabels = Arrays.copyOf(mat.rowLabels, mat.rowLabels.length);
		if (mat.columnLabels != null)
			columnLabels = Arrays.copyOf(mat.columnLabels, mat.columnLabels.length);

		if (mat.index != null)
			index = Arrays.copyOf(mat.index, mat.index.length);
	}

	public ColtMatrix(int rows, int columns) {
		this();
		data = DoubleFactory2D.sparse.make(rows,columns);
		nRows = rows;
		nColumns = columns;
		rowLabels = new String[rows];
		columnLabels = new String[columns];
		index = null;
	}

	public ColtMatrix(int rows, int columns, double initialValue) {
		this(rows, columns);
		data.assign(initialValue);
	}

	public ColtMatrix(int rows, int columns, DISTRIBUTION dist) {
		this(rows, columns);
		if (dist.equals(DISTRIBUTION.NORMAL))
			data.assign(new Normal(0.0, 1.0, DoubleRandomEngine.makeDefault()));
		else if (dist.equals(DISTRIBUTION.BINOMIAL))
			data.assign(new Binomial(1, 0.5, DoubleRandomEngine.makeDefault()));
	}

	public ColtMatrix(ColtMatrix mat, DoubleMatrix2D data) {
		this();
		transposed = mat.transposed;
		symmetric = mat.symmetric;
		if (mat.rowLabels != null)
			rowLabels = Arrays.copyOf(mat.rowLabels, mat.rowLabels.length);
		if (mat.columnLabels != null)
			columnLabels = Arrays.copyOf(mat.columnLabels, mat.columnLabels.length);

		this.data = data;
		nRows = data.rows();
		nColumns = data.columns();
		updateMinMax();
	}

	public ColtMatrix(SimpleMatrix mat) {
		this();
		data = DoubleFactory2D.sparse.make(mat.toArray());
		nRows = data.rows();
		nColumns = data.columns();
		transposed = mat.transposed;
		symmetric = mat.symmetric;
		minValue = mat.minValue;
		maxValue = mat.maxValue;
		if (mat.index != null)
			index = Arrays.copyOf(mat.index, mat.index.length);
	}

	public MatrixOps ops() { return ops; }

	public Matrix like() {
		return new ColtMatrix();
	}

	public Matrix like(int rows, int columns) {
		return new ColtMatrix(rows, columns);
	}

	public Matrix like(int rows, int columns, double initialValue) {
		return new ColtMatrix(rows, columns, initialValue);
	}

	public Matrix like(int rows, int columns, DISTRIBUTION dist) {
		return new ColtMatrix(rows, columns, dist);
	}

	public Matrix like(Matrix initial) {
		if (initial instanceof ColtMatrix) {
			return new ColtMatrix(this, ((ColtMatrix)initial).data);
		} else {
			ColtMatrix newMat = new ColtMatrix();
			newMat.initialize(initial.nRows(), initial.nColumns(), initial.toArray());
			return newMat;
		}
	}

	public Matrix like(int rows, int columns, double[][] initial) {
		Matrix newMat = like();
		newMat.initialize(rows, columns, initial);
		return newMat;
	}

	public void initialize(int rows, int columns, double[][] arrayData) {
		if (arrayData != null) {
			data = DoubleFactory2D.sparse.make(arrayData);
		} else {
			data = DoubleFactory2D.sparse.make(rows, columns);
		}
		nRows = data.rows();
		nColumns = data.columns();
		transposed = false;
		symmetric = false;
		rowLabels = new String[nRows];
		columnLabels = new String[nColumns];
		updateMinMax();
	}

	public void initialize(int rows, int columns, Double[][] arrayData) {
		data = DoubleFactory2D.sparse.make(rows, columns);
		nRows = data.rows();
		nColumns = data.columns();
		if (arrayData != null) {
			for (int row = 0; row < rows; row++) {
				for (int col = 0; col < columns; col++) {
					setValue(row, col, arrayData[row][col]);
				}
			}
		}
		transposed = false;
		symmetric = false;
		rowLabels = new String[nRows];
		columnLabels = new String[nColumns];
		updateMinMax();
	}

	/**
	 * Return the number of rows in this matrix.
	 *
	 * @return number of rows
	 */
	public int nRows() { 
		if (index != null)
			return index.length;
		return nRows; 
	}

	/**
	 * Return the number of columns in this matrix.
	 *
	 * @return number of columns
	 */
	public int nColumns() { return nColumns; }

	/**
	 * Return the value at a specific location.
	 *
	 * @param row the row number of the value
	 * @param column the coulmn number of the value
	 * @return the (possibly null) value at that location
	 */
	public Double getValue(int row, int column) { 
		double d = doubleValue(row, column);
		if (Double.isNaN(d))
			return null;
		return d;
	}

	/**
	 * Return the value at a specific location.
	 *
	 * @param row the row number of the value
	 * @param column the coulmn number of the value
	 * @return the value at that location, if it was set, otherwise, return Double.NaN.
	 */
	public double doubleValue(int row, int column) {
		double d;
		if (index == null)
			d = data.getQuick(row, column);
		else
			d = data.getQuick(index[row], index[column]);
		return d;
	}

	/**
	 * Set the value at a specific location.
	 *
	 * @param row the row number of the value
	 * @param column the coulmn number of the value
	 * @param value the value to set
	 */
	public void setValue(int row, int column, double value) {
		if (value < minValue) minValue = value;
		if (value > maxValue) maxValue = value;

		if (index != null) {
			row = index[row];
			column = index[column];
		}

		data.setQuick(row, column, value);
	}

	/**
	 * Set the value at a specific location.
	 *
	 * @param row the row number of the value
	 * @param column the coulmn number of the value
	 * @param value the value to set
	 */
	public void setValue(int row, int column, Double value) {
		if (value != null) {
			if (value < minValue) minValue = value;
			if (value > maxValue) maxValue = value;
		}

		if (index != null) {
			row = index[row];
			column = index[column];
		}

		if (value == null)
			data.setQuick(row, column, Double.NaN);
		else
			data.setQuick(row, column, value);
	}

	/**
	 * Return true if the location has a value
	 *
	 * @param row the row number of the value
	 * @param column the coulmn number of the value
	 * @return true if this location has a value, false otherwise
	 */
	public boolean hasValue(int row, int column) {
		double d = doubleValue(row, column);
		if (Double.isNaN(d))
			return false;
		return true;
	}
	
	/**
	 * Return an array of column labels
	 *
	 * @return the column labels
	 */
	public String[] getColumnLabels() {
		return columnLabels;
	}

	/**
	 * Return a column label
	 *
	 * @param col the column to get the label for
	 * @return the column label
	 */
	public String getColumnLabel(int col) {
		if (index != null)
			col = index[col];
		return columnLabels[col];
	}

	/**
	 * Set a column label
	 *
	 * @param col the column to set the label for
	 * @param label the column label
	 */
	public void setColumnLabel(int col, String label) {
		if (index != null)
			col = index[col];
		columnLabels[col] = label;
	}

	/**
	 * Set the column labels
	 *
	 * @param labelList the list of column labels
	 */
	public void setColumnLabels(List<String>labelList) {
		columnLabels = labelList.toArray(new String[0]);
	}

	/**
	 * Return an array of row labels
	 *
	 * @return the row labels
	 */
	public String[] getRowLabels() {
		return rowLabels;
	}
	
	/**
	 * Return a row label
	 *
	 * @param row the row to get the label for
	 * @return the row label
	 */
	public String getRowLabel(int row) {
		if (index != null)
			row = index[row];
		return rowLabels[row];
	}

	/**
	 * Set a row label
	 *
	 * @param row the row to set the label for
	 * @param label the row label
	 */
	public void setRowLabel(int row, String label) {
		if (index != null)
			row = index[row];
		rowLabels[row] = label;
	}
	
	/**
	 * Set the row labels
	 *
	 * @param labelList the list of row labels
	 */
	public void setRowLabels(List<String>labelList) {
		rowLabels = labelList.toArray(new String[0]);
	}

	/**
	 * Return the distance between rows based on the metric.
	 *
	 * @param metric the metric to use to calculate the distances
	 * @return a new Matrix of the distances between the rows
	 */
	public Matrix getDistanceMatrix(DistanceMetric metric) {
		ColtMatrix mat = new ColtMatrix(nRows, nRows);
		mat.transposed = false;
		mat.symmetric = true;
		mat.rowLabels = Arrays.copyOf(rowLabels, rowLabels.length);
		mat.columnLabels = Arrays.copyOf(rowLabels, rowLabels.length);

		IntStream.range(0, nRows).parallel()
			.forEach(row -> IntStream.range(row, nRows)
				.forEach(column -> {
						mat.setValue(row, column, metric.getMetric(this, this, row, column));
						if (row != column)
							mat.setValue(column, row, metric.getMetric(this, this, row, column));
				}));
		return mat;
	}
 
	/**
	 * Return a 2D array with all of the values in the matrix.  The missing
	 * values are set to Double.NaN
	 *
	 * @return the data in the matrix
	 */
	public double[][] toArray() {
		double doubleData[][] = new double[nRows][nColumns];
		for (int row = 0; row < nRows; row++) {
			for (int col = colStart(row); col < nColumns; col++) {
				doubleData[row][col] = doubleValue(row, col);
				if (symmetric && row != col)
					doubleData[col][row] = doubleValue(row, col);
			}
		}
		return doubleData;
	}

	public double[] getRow(int row) {
		return data.viewRow(row).toArray();
	}

	public double[] getColumn(int col) {
		return data.viewColumn(col).toArray();
	}

	/**
	 * Return the maximum value in the matrix
	 *
	 * @return the max value
	 */
	public double getMaxValue() {
		return maxValue;
	}

	/**
	 * Return the minimum value in the matrix
	 *
	 * @return the min value
	 */
	public double getMinValue() {
		return minValue;
	}

	/**
	 * Return true if the matrix is transposed
	 *
	 * @return true if the matrix is transposed
	 */
	public boolean isTransposed() {
		return transposed;
	}

	/**
	 * Set true if the matrix is transposed
	 *
	 * @param transposed true if the matrix is transposed
	 */
	public void setTransposed(boolean transposed) {
		this.transposed = transposed;
	}

	/**
	 * Return true if the matrix is symmetraical
	 *
	 * @return true if the matrix is symmetraical
	 */
	public boolean isSymmetrical() {
		return symmetric;
	}

	/**
	 * Set true if the matrix is symmetrical
	 *
	 * @param symmetrical true if the matrix is symmetrical
	 */
	public void setSymmetrical(boolean symmetrical) {
		this.symmetric = symmetrical;
	}

	/**
	 * Set all missing values to zero
	 */
	public void setMissingToZero() {
		for (int row = 0; row < nRows; row++) {
			for (int col = colStart(row); col < nColumns; col++) {
				if (Double.isNaN(doubleValue(row, col))) {
					data.setQuick(row, col, 0.0d);
					if (symmetric && row != col)
						data.setQuick(col, row, 0.0d);
				}
			}
		}
	}

	/**
	 * Adjust the diagonals
	 */
	public void adjustDiagonals() {
		for (int row = 0; row < nRows; row++ ) {
			double max = 0.0;
			for (int col = 0; col < nColumns; col++ ) {
				if (data.get(row,col) > max) max = data.get(row,col);
			}
			data.setQuick(row, row, max);
		}
	}

	/**
	 * Return the rank order of the columns in a row
	 *
	 * @param row the row to rank the columns in
	 * @return the rank order of the columns
	 */
	public double[] getRank(int row) {
		// Get the masked row
		double[] tData = new double[nColumns];
		int nVals = 0;
		for (int column = 0; column < nColumns; column++) {
			if (hasValue(row,column))
				tData[nVals++] = doubleValue(row,column);
		}
		//System.out.println("Inside getRank; nVals: "+nVals);
		if (nVals == 0)
			return null;

		// Sort the data
		Integer index[] = MatrixUtils.indexSort(tData,nVals);

		// Build a rank table
		double[] rank = new double[nVals];
		for (int i = 0; i < nVals; i++) rank[index[i]] = i;

		// Fix for equal ranks
		int i = 0;
		while (i < nVals) {
			int m = 0;
			double value = tData[index[i]];
			int j = i+1;
			while (j < nVals && tData[index[j]] == value) j++;
			m = j - i; // Number of equal ranks found
			value = rank[index[i]] + (m-1)/2.0;
			for (j = i; j < i+m; j++) rank[index[j]] = value;
			i += m;
		}

		return rank;
	}

	/**
	 * Create an index on the matrix
	 */
	public void index() {
		if (!symmetric) {
			// Can't index a non-symmetric matrix!
			logger.warn("clusterMaker2 ColtMatrix: attempt to index an assymetric network");
			return;
		}

		// initialize indexing array to original order
		index = new int[nRows];
		for (int i = 0; i < index.length; ++i) {
			index[i] = i;
		}
	}
	
	/**
	 * Create a shallow copy of the matrix with an alternative
	 * index.  This is an efficient way to access submatrices
	 */
	public Matrix submatrix(int[] index) {
		ColtMatrix mat = new ColtMatrix();
		mat.data = data;
		mat.index = index;
		mat.nRows = nRows;
		mat.nColumns = nColumns;
		mat.symmetric = symmetric;
		mat.transposed = transposed;
		mat.rowLabels = rowLabels;
		mat.columnLabels = columnLabels;
		mat.maxValue = maxValue;
		mat.minValue = minValue;
		return mat;
	}

	/**
	 * Return a submatrix
	 * 
	 * @param row the starting row of the submatrix
	 * @param col the starting column of the submatrix
	 * @param rows the number of rows
	 * @param cols the number of columnss
	 * @return submatrix
	 */
	public Matrix submatrix(int row, int col, int rows, int cols) {
		ColtMatrix newMatrix = new ColtMatrix(rows, cols);
		newMatrix.data = data.viewPart(row, col, rows, cols);
		double newMin = Double.MAX_VALUE;
		double newMax = Double.MIN_VALUE;
		for (int r = 0; r < rows; r++) {
			newMatrix.setRowLabel(r, rowLabels[r+row]);
			for (int c = 0; c < cols; c++) {
				if (r == 0)
					newMatrix.setColumnLabel(c, columnLabels[c+col]);
			}
		}
		newMatrix.minValue = newMin;
		newMatrix.maxValue = newMax;

		if (transposed)
			newMatrix.setTransposed(transposed);
		if (symmetric && rows == cols && row == col)
			newMatrix.setSymmetrical(symmetric);
		return newMatrix;
	}

	/**
	 * Return a copy of the Matrix
	 *
	 * @return matrix copy
	 */
	public Matrix copy() {
		return new ColtMatrix(this);
	}

	private DoubleMatrix2D create2DMatrix (DoubleMatrix1D[] rows) {
		int columns = (int)rows[0].size();
		DoubleMatrix2D C = DoubleFactory2D.sparse.make(rows.length, columns);
		for (int row = 0; row < rows.length; row++) {
			for (int col = 0; col < columns; col++) {
				double value = rows[row].getQuick(col);
				if (value != 0.0)
					C.setQuick(row, col, value);
			}
		}
		return C;
	}

	public DoubleMatrix2D getColtMatrix() {
		return data;
	}

	/**
	 * Debugging routine to print out information about a matrix
	 *
	 * @param matrix the matrix we're going to print out information about
	 */
	public String printMatrixInfo() {
		String s = "Colt Matrix("+data.rows()+", "+data.columns()+")\n";
		if (data.getClass().getName().indexOf("Sparse") >= 0)
			s += " matrix is sparse\n";
		else
			s += " matrix is dense\n";
		s += " cardinality is "+data.cardinality()+"\n";
		return s;
	}

	public String printMatrix() {
		StringBuilder sb = new StringBuilder();
		sb.append("ColtMatrix("+nRows+", "+nColumns+")\n");
		if (data.getClass().getName().indexOf("Sparse") >= 0)
			sb.append(" matrix is sparse\n");
		else
			sb.append(" matrix is dense\n");
		sb.append(" cardinality is "+data.cardinality()+"\n\t");

		for (int col = 0; col < nColumns; col++) {
			if (columnLabels != null)
				sb.append(getColumnLabel(col)+"\t");
			else
				sb.append("null\t");
		}
		sb.append("\n");
		for (int row = 0; row < nRows; row++) {
			if (rowLabels != null)
				sb.append(getRowLabel(row)+":\t"); //node.getIdentifier()
			else
				sb.append("null:\t"); //node.getIdentifier()
			for (int col = 0; col < nColumns; col++) {
				double value = doubleValue(row, col);
				if (value < 0.001)
					sb.append(""+scFormat.format(value)+"\t");
				else
					sb.append(""+format.format(value)+"\t");
			} 
			sb.append("\n");
		} 
		return sb.toString();
	}

	public void writeMatrix(String fileName) {
		String tmpDir = System.getProperty("java.io.tmpdir");
		String filePath = tmpDir + File.separator + fileName;
		try{
			File file = new File(filePath);
			if(!file.exists()) {
				file.createNewFile();
			}
			PrintWriter writer = new PrintWriter(filePath, "UTF-8");
			writer.write(printMatrix());
			writer.close();
		}catch(IOException e){
			e.printStackTrace(System.out);
		}
	}

	public SimpleMatrix getSimpleMatrix() {
		SimpleMatrix sm = new SimpleMatrix(nRows, nColumns);
		double[][] inputData = toArray();
		for (int row = 0; row < nRows; row++) {
			for (int column = 0; column < nColumns; column++) {
				sm.data[row][column] = inputData[row][column];
			}
		}
		sm.transposed = this.transposed;
		sm.symmetric = this.symmetric;
		sm.minValue = this.minValue;
		sm.maxValue = this.maxValue;
		sm.index = Arrays.copyOf(this.index, this.index.length);
		return sm;
	}
	
	protected Matrix copyDataFromMatrix(DoubleMatrix2D matrix2D) {
		ColtMatrix mat = new ColtMatrix();
		double[][]inputData = matrix2D.toArray();
		mat.initialize(matrix2D.rows(), matrix2D.columns(), inputData);
		mat.symmetric = true;
		mat.data = matrix2D;
		String[] labels;
		if (this.transposed)
			labels = rowLabels;
		else
			labels = columnLabels;
		if (labels != null) {
			mat.rowLabels = Arrays.copyOf(labels, labels.length);
			mat.columnLabels = Arrays.copyOf(labels, labels.length);
		}
		return mat;
	}

	public void updateMinMax() {
		double[] min = data.getMinLocation();
		double[] max = data.getMaxLocation();
		minValue = min[0];
		maxValue = max[0];
	}

	protected int colStart(int row) {
		if (!symmetric) return 0;
		return row;
	}
}
