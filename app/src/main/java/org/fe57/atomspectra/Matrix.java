package org.fe57.atomspectra;

public class Matrix {
    public double[][] array = null;

    //create non-matrix
    public Matrix() {
    }

    //create matrix
    public Matrix(int rows, int cols) {
        if (rows < 1 || cols < 1)
            array = null;
        array = new double[rows][cols];
    }

    public Matrix(Matrix matrix) {
        if (matrix.array == null)
            return;
        array = new double[matrix.array.length][matrix.array[0].length];
        for (int i = 0; i < array.length; i++)
            System.arraycopy(matrix.array[i], 0, array[i], 0, array[0].length);
    }

    public boolean isEmpty() {
        return array == null;
    }

    //Get identity matrix with ones at the main diagonal line
    public static Matrix Identity(int rows, int cols) {
        if (rows < 1 || cols < 1)
            return new Matrix();
        Matrix out = new Matrix(rows, cols);
        for (int i = 0; i < Math.min(rows, cols); i++)
            out.array[i][i] = 1.0;
        return out;
    }

    //Get zero matrix
    public static Matrix Zero(int rows, int cols) {
        if (rows < 1 || cols < 1)
            return new Matrix();
        return new Matrix(rows, cols);
    }

    //C=this+other
    public Matrix Add(Matrix other) {
        if (array == null)
            return new Matrix(0, 0);
        if (array.length != other.array.length)
            return new Matrix(0, 0);
        if (array[0].length != other.array[0].length)
            return new Matrix(0, 0);
        Matrix tmp = new Matrix(this);
        for (int i = 0; i < array.length; i++) {
            for (int j = 0; j < array[0].length; j++)
                tmp.array[i][j] += other.array[i][j];
        }
        return tmp;
    }

    //C=this-other
    public Matrix Sub(Matrix other) {
        if (array == null)
            return new Matrix(0, 0);
        if (array.length != other.array.length)
            return new Matrix(0, 0);
        if (array[0].length != other.array[0].length)
            return new Matrix(0, 0);
        Matrix tmp = new Matrix(this);
        for (int i = 0; i < array.length; i++) {
            for (int j = 0; j < array[0].length; j++)
                tmp.array[i][j] -= other.array[i][j];
        }
        return tmp;
    }

    //C=this^T
    public Matrix Transpose() {
        if (array == null)
            return new Matrix(0, 0);
        double[][] tmp = new double[array[0].length][array.length];
        for (int i = 0; i < array.length; i++)
            for (int j = 0; j < array[0].length; j++)
                tmp[j][i] = array[i][j];
        Matrix out = new Matrix();
        out.array = tmp;
        return out;
    }

    //C=this^(-1)
    public Matrix Inverse() {
        if (array == null)
            return new Matrix(0, 0);
        if (array.length != array[0].length)
            return new Matrix(0, 0);
        //I'll use 2 matrices: (A|E) not combined together
        int array_size = array.length;
        double[][] A = new double[array_size][array_size];
        double[][] E = new double[array_size][array_size];
        for (int i = 0; i < array_size; i++) {
            System.arraycopy(array[i], 0, A[i], 0, array_size);
            E[i][i] = 1.0;
        }
        //calculate the inverse matrix
        //forward steps
        double reduce;
        for (int i = 0; i < array_size; i++) {
            if (A[i][i] == 0) {
                int non_zero = -1;
                for (int j = i + 1; j < array_size; j++) {
                    if (A[j][i] != 0.0) {
                        non_zero = j;
                        break;
                    }
                }
                //I can't calculate the inverse matrix
                if (non_zero == -1) {
                    return new Matrix();
                }
                double swap;
                //swap two lines
                for (int j = 0; j < array_size; j++) {
                    swap = A[non_zero][j];
                    A[non_zero][j] = A[i][j];
                    A[i][j] = swap;
                    swap = E[non_zero][j];
                    E[non_zero][j] = E[i][j];
                    E[i][j] = swap;
                }
                //now we have line i with non-zero element at [i][i];
            }
            //make A[i][i] equal to 1.0
            reduce = A[i][i];
            for (int j = 0; j < array_size; j++) {
                A[i][j] /= reduce;
                E[i][j] /= reduce;
            }
            //subtract line i from other subsequent lines
            for (int j = i + 1; j < array_size; j++) {
                reduce = A[j][i];
                for (int k = 0; k < array_size; k++) {
                    A[j][k] -= reduce * A[i][k];
                    E[j][k] -= reduce * E[i][k];
                }
            }
        }
        //reverse steps
        for (int i = array_size - 1; i >= 1; i--) {
            for (int j = i - 1; j >=0; j--) {
                reduce = A[j][i];
                for (int k = 0; k < array_size; k++) {
                    A[j][k] -= reduce * A[i][k];
                    E[j][k] -= reduce * E[i][k];
                }
            }
        }
        //prepare output
        Matrix out = new Matrix();
        out.array = E;
        return out;
    }

    //C=this*matrix
    public Matrix Times(Matrix matrix) {
        if (array == null)
            return new Matrix(0, 0);
        if (matrix.array == null)
            return new Matrix(0, 0);
        if (array[0].length != matrix.array.length)
            return new Matrix(0, 0);

        Matrix out = new Matrix(array.length, matrix.array[0].length);
        for (int i = 0; i < array.length; i++) {
            for (int j = 0; j < matrix.array[0].length; j++)
                for (int k = 0; k < array[0].length; k++)
                    out.array[i][j] += array[i][k] * matrix.array[k][j];
        }
        return out;
    }

}
