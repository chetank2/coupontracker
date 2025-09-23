#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
from flask import Flask, render_template, send_from_directory

app = Flask(__name__, 
            static_folder='web_ui/static',
            template_folder='web_ui/templates')

@app.route('/')
def index():
    """Render a simple page"""
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <title>CouponTracker Web UI</title>
        <style>
            body { font-family: Arial, sans-serif; margin: 40px; line-height: 1.6; }
            h1 { color: #333; }
            .container { max-width: 800px; margin: 0 auto; }
            .card { border: 1px solid #ddd; border-radius: 8px; padding: 20px; margin-bottom: 20px; }
            .btn { display: inline-block; background: #4CAF50; color: white; padding: 10px 15px; 
                   text-decoration: none; border-radius: 4px; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>CouponTracker Web UI</h1>
            <p>This is a simplified version of the CouponTracker Web UI.</p>
            
            <div class="card">
                <h2>Model Information</h2>
                <p>The model is currently configured to run locally on the device.</p>
                <p>To update the model in the Android app, you would normally use the "Update App Model" button.</p>
            </div>
            
            <div class="card">
                <h2>Training</h2>
                <p>Training functionality is available in the full version of the Web UI.</p>
                <p>The trained model is used for coupon recognition in the Android app.</p>
            </div>
            
            <div class="card">
                <h2>Testing</h2>
                <p>Testing functionality is available in the full version of the Web UI.</p>
                <p>You can test the model with new coupon images to see how well it recognizes different elements.</p>
            </div>
        </div>
    </body>
    </html>
    """

if __name__ == "__main__":
    app.run(debug=True, host='0.0.0.0', port=5002)
