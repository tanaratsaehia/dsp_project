import pandas as pd
import numpy as np

class PreprocessAccel:
    def __init__(self, sampling_rate):
        self.sampling_rate = sampling_rate
    
    def calculate_magnitude(self, x, y, z):
        """Return the magnitude from x, y, z components."""
        return np.sqrt(x**2 + y**2 + z**2)

    def segment_and_flatten_magnitude(self, label, df, magnitude_column_name=None, window_size_sec=5, overlap=0.5):
        """
        1) Combine x, y, z signals into a single magnitude signal.
        2) Segment the magnitude signal into windows of window_size_sec seconds 
        with the specified overlap.
        3) Flatten each window into columns: start_time, x1, x2, ..., xN 
        (where N = window_size_sec * sampling_rate).
        
        Parameters:
            df (pd.DataFrame): DataFrame containing columns 'time', 'x', 'y', 'z'.
                            'time' is in milliseconds.
            magnitude_column_name (string): Name of calculated magnitude.
            window_size_sec (float): Length of each window in seconds (default 5).
            overlap (float): Fraction of window overlap (default 0.5 = 50%).
            sampling_rate (int): Sampling rate in Hz (default 50).
            
        Returns:
            pd.DataFrame: Each row corresponds to a flattened window.
                        Columns:
                        - 'start_time': millisecond timestamp of the window start
                        - 'x1', 'x2', ..., 'xN': magnitude values for each sample
        """
        # Sort by time to ensure chronological order
        df = df.sort_values(by='time').reset_index(drop=True)
        
        # 1) Compute magnitude for each row
        if not magnitude_column_name:
            df['magnitude'] = self.calculate_magnitude(df['x'], df['y'], df['z'])

        # 2) Calculate number of samples per window and step size
        window_samples = int(window_size_sec * self.sampling_rate)  # e.g., 5s * 50Hz = 250
        step_size = int(window_samples * (1 - overlap))        # e.g., 250 * 0.5 = 125

        flattened_rows = []

        # 3) Loop through data with the given step size
        for start_idx in range(0, len(df) - window_samples + 1, step_size):
            # Extract this window of magnitude values
            window = df.iloc[start_idx : start_idx + window_samples].reset_index(drop=True)
            
            # Prepare a dict for one flattened row
            row_dict = {}
            row_dict['start_time'] = window.loc[0, 'time']
            row_dict['label'] = label
            
            # Flatten magnitude into x1, x2, x3, ..., xN
            for i in range(window_samples):
                if magnitude_column_name:
                    row_dict[f'x{i+1}'] = window.loc[i, magnitude_column_name]
                else:
                    row_dict[f'x{i+1}'] = window.loc[i, 'magnitude']
            
            flattened_rows.append(row_dict)

        # Convert list of dicts into a DataFrame
        return pd.DataFrame(flattened_rows)
    
    def compute_fft_on_flattened_data(self, df, num_samples=250):
        """
        For each row (window) in the DataFrame, compute the FFT of the flattened time-series 
        in columns x1...xN, take the magnitude spectrum, and keep only the first half 
        (non-redundant part).
        
        The returned DataFrame contains:
        - 'start_time' and 'label'
        - 'fft1', 'fft2', ... up to fft(M) where M = num_samples//2 + 1
        """
        feature_columns = [f'x{i+1}' for i in range(num_samples)]
        fft_features_list = []
        
        # Loop through each row (each window)
        for idx, row in df.iterrows():
            # Extract the time-series for this window and convert to float
            ts = row[feature_columns].values.astype(np.float64)
            # Compute the FFT
            fft_vals = np.fft.fft(ts)
            # Compute magnitude (absolute value)
            fft_mag = np.abs(fft_vals)
            # Keep only the first half (+1 to include DC component)
            half_fft = fft_mag[:num_samples//2 + 1]
            fft_features_list.append(half_fft)
        
        # Create a DataFrame for FFT features with column names fft1, fft2, ...
        num_fft_features = num_samples//2 + 1
        fft_df = pd.DataFrame(
            fft_features_list, 
            columns=[f'x{i+1}' for i in range(num_fft_features)]
        )
        
        # Combine the start_time and label with the FFT features
        result_df = pd.concat([df[['start_time', 'label']].reset_index(drop=True), fft_df], axis=1)
        return result_df
