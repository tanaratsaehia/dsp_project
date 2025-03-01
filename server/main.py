from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import numpy as np
from scipy.signal import butter, lfilter
import joblib

model = joblib.load("rf_model.pkl")
scaler = joblib.load("scaler.pkl")
label_encoder = joblib.load("label_encoder.pkl")

idx2name = {index: label for index, label in enumerate(label_encoder.classes_)}

class PredictionRequest(BaseModel):
    data: list[float]  # Expecting a single data instance

app = FastAPI()

@app.post("/predict")
async def predict(request: PredictionRequest):
    try:
        # Convert input data to NumPy array
        input_data = np.array(request.data).reshape(1, -1)
        
        # Ensure input shape matches expected input shape
        if input_data.shape[1] != 100:
            raise HTTPException(status_code=400, detail=f"Invalid input shape. Expected 100 features, got {input_data.shape[1]}")
        
        # Apply bandpass filter
        input_data = bandpass_filter(input_data)
        
        # Compute Fast Fourier Transform
        input_data = compute_fft(input_data)
        
        # Normalize input
        input_data = scaler.transform(input_data)
        
        # Predict class probabilities
        probabilities = model.predict_proba(input_data)[0].tolist()
        predicted_index = np.argmax(probabilities)
        predicted_class = idx2name[predicted_index]
        
        return_value = {"predicted_index": int(predicted_index), 
                        "predicted_class": predicted_class, 
                        "probabilities": probabilities, 
                        "names": idx2name,
                        }
        return return_value
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/")
def home():
    return {"message": "MLP Prediction API is running"}

# Bandpass filter function
def bandpass_filter(data, lowcut=0.4, highcut=15, fs=50, order=5):
    nyq = 0.5 * fs
    low = lowcut / nyq
    high = highcut / nyq
    b, a = butter(order, [low, high], btype='band')
    y = lfilter(b, a, data, axis=1)  # Apply filtering along the correct axis
    return y

# Compute Fast Fourier Transform
def compute_fft(data, num_samples=100):
    fft_features_list = []
    for row in data:
        fft_vals = np.fft.fft(row.astype(np.float64))  # Ensure computation uses float64 for precision
        fft_mag = np.abs(fft_vals)  # Compute magnitude spectrum
        half_fft = fft_mag[:num_samples // 2 + 1]  # Keep first half (+1 for DC component)
        fft_features_list.append(half_fft)
    
    return np.array(fft_features_list)  # Convert final output to float32

# run this code with:
    # uvicorn main:app --host 0.0.0.0 --port 8000