import { createApp } from 'vue'
import App from './App.vue'
import * as L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import 'leaflet-rotatedmarker'
import './style.css'

// some leaflet plugins/helpers expect the global
window.L = L

createApp(App).mount('#app')
