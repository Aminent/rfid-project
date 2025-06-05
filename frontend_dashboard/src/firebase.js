// Import the functions you need from the SDKs you need
import { initializeApp } from "firebase/app";
import { getAnalytics } from "firebase/analytics";
// TODO: Add SDKs for Firebase products that you want to use
// https://firebase.google.com/docs/web/setup#available-libraries

// Your web app's Firebase configuration
// For Firebase JS SDK v7.20.0 and later, measurementId is optional
const firebaseConfig = {
  apiKey: "AIzaSyDeFMoef0bZGv7vwl3Cu0uHKy5NUSvfeKc",
  authDomain: "rfid-project-6f501.firebaseapp.com",
  projectId: "rfid-project-6f501",
  storageBucket: "rfid-project-6f501.firebasestorage.app",
  messagingSenderId: "710653692034",
  appId: "1:710653692034:web:ddb9a6f870b8bdeb334c9b",
  measurementId: "G-FNX4GWC0VB"
};

// Initialize Firebase
const app = initializeApp(firebaseConfig);
const analytics = getAnalytics(app);