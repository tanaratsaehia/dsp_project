from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import numpy as np
from scipy.signal import butter, lfilter
import joblib

pipeline = joblib.load('activity_recognition_pipeline.pkl')
model = pipeline['pipeline']
le = pipeline['label_encoder']

idx2name = {index: label for index, label in enumerate(le.classes_)}

class PredictionRequest(BaseModel):
    data: list[float]  # Expecting a single data instance

app = FastAPI()

@app.post("/predict")
async def predict(request: PredictionRequest):
    try:
        # Convert input data to NumPy array
        input_data = np.array(request.data).reshape(1, -1)

        # Apply bandpass filter
        filtered_magnitude = bandpass_filter(input_data)

        # Compute FFT with fixed length
        fft_features = compute_fft(filtered_magnitude, expected_length=126)
        fft_features = np.array(fft_features).reshape(1, -1)

        # Predict class probabilities
        probabilities = model.predict_proba(fft_features)[0].tolist()
        probabilities = [round(prob, 5) for prob in probabilities]
        predicted_index = np.argmax(probabilities)
        predicted_class = idx2name[predicted_index]

        return {
            "predicted_index": int(predicted_index), 
            "predicted_class": predicted_class, 
            "probabilities": probabilities, 
            # "names": idx2name,
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/")
def home():
    return {"message": "MLP Prediction API is running"}

# Function to apply band-pass filter
def bandpass_filter(data, lowcut=0.4, highcut=15, fs=50, order=5):
    nyq = 0.5 * fs
    low = lowcut / nyq
    high = highcut / nyq
    b, a = butter(order, [low, high], btype='band')
    return lfilter(b, a, data)

# Function to compute FFT
def compute_fft(data, expected_length=126):
    # Ensure data is 1D before applying FFT
    if data.ndim == 2:
        data = data.flatten()  # Convert (1, N) to (N,)
    
    fft_vals = np.fft.fft(data)
    fft_mag = np.abs(fft_vals)
    
    # Ensure the FFT output length is consistent
    cut_fft_mag = fft_mag[:expected_length]
    return cut_fft_mag



# run this code with:
    # uvicorn main:app --host 0.0.0.0 --port 8000