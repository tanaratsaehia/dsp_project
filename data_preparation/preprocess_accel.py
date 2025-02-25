import pandas as pd
import numpy as np

class PreprocessAccel:
    def __init__(self, sampling_rate):
        self.sampling_rate = sampling_rate
    
    def segment_accelerometer_data(self, df, window_size_sec=5, overlap=0.5):
        """
        Segments accelerometer data into overlapping windows.

        Parameters:
            df (pd.DataFrame): DataFrame containing columns 'time', 'x', 'y', 'z'.
                            The 'time' column is in milliseconds.
            window_size_sec (float): Length of each window in seconds (default 5 seconds).
            overlap (float): Fraction of window overlap (default 0.5 for 50% overlap).
            sampling_rate (int): Sampling rate in Hz (default 50Hz).

        Returns:
            pd.DataFrame: A new DataFrame where each row represents a window.
                        It contains:
                            - 'start_time': starting time (ms) of the window,
                            - 'window_data': a DataFrame for that segment containing the original columns.
        """
        # Calculate number of samples per window and the step size
        window_samples = int(window_size_sec * self.sampling_rate)  # e.g., 5 * 50 = 250 samples
        step_size = int(window_samples * (1 - overlap))          # e.g., 250 * 0.5 = 125 samples

        segments = []
        start_times = []

        # Ensure the DataFrame is sorted by time
        df = df.sort_values(by='time').reset_index(drop=True)

        # Loop over the data with the calculated step size
        for start in range(0, len(df) - window_samples + 1, step_size):
            # Extract the window segment
            window = df.iloc[start:start + window_samples].reset_index(drop=True)
            segments.append(window)
            # Record the starting time (first timestamp of the window)
            start_times.append(window.loc[0, 'time'])

        # Create a DataFrame that summarizes each window segment
        segmented_df = pd.DataFrame({
            'start_time': start_times,
            'window_data': segments
        })

        return segmented_df
    
    def segment_and_flatten_accelerometer_data(self, df, window_size_sec=5, overlap=0.5):
        """
        Segments accelerometer data into overlapping windows and flattens each window.

        Parameters:
            df (pd.DataFrame): DataFrame with columns 'time', 'x', 'y', 'z'.
                            'time' is in milliseconds.
            window_size_sec (float): Length of each window in seconds (default 5).
            overlap (float): Fraction of window overlap (default 0.5 for 50%).
            sampling_rate (int): Sampling rate in Hz (default 50Hz).

        Returns:
            pd.DataFrame: A DataFrame where each row corresponds to a single window.
                        The columns are:
                            - 'start_time': The start time (ms) of the window
                            - 'x1', 'y1', 'z1', ..., 'xN', 'yN', 'zN'
                            (Flattened accelerometer values over the window)
        """
        # Number of samples in each window
        window_samples = int(window_size_sec * self.sampling_rate)  # e.g., 5s * 50Hz = 250
        # Step size (for overlap)
        step_size = int(window_samples * (1 - overlap))        # e.g., 250 * 0.5 = 125

        # Sort by time to ensure chronological order
        df = df.sort_values(by='time').reset_index(drop=True)

        # List to hold each flattened window
        flattened_rows = []

        for start in range(0, len(df) - window_samples + 1, step_size):
            # Extract the window
            window = df.iloc[start:start + window_samples].reset_index(drop=True)

            # Build a dictionary for the flattened row
            row_dict = {}
            # Record the start time of this window
            row_dict['start_time'] = window.loc[0, 'time']

            # Flatten the x, y, z columns
            # i.e., x1, y1, z1, x2, y2, z2, ...
            for i in range(window_samples):
                row_dict[f'x{i+1}'] = window.loc[i, 'x']
                row_dict[f'y{i+1}'] = window.loc[i, 'y']
                row_dict[f'z{i+1}'] = window.loc[i, 'z']

            flattened_rows.append(row_dict)

        # Create a DataFrame from all flattened rows
        flattened_df = pd.DataFrame(flattened_rows)
        return flattened_df