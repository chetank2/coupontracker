#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import json
import uuid
from datetime import datetime
from pathlib import Path
from flask import Flask, render_template, request, jsonify, send_from_directory, redirect, url_for, send_file
from pydantic import ValidationError

# Add current directory to path
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(current_dir)
sys.path.append(current_dir)
sys.path.append(parent_dir)

# Import utility modules
from web_ui.utils.model_manager import ModelManager
from web_ui.utils.image_processor import ImageProcessor
from ml.dataset_manager import DatasetManager
from ml.preannotation import generate_candidates
from ml.orchestrator import TrainingJobManager, JobRequest as JobRequestSchema
from web_ui.utils.evaluation_manager import EvaluationManager
from ml.packaging.registry import PackagingRegistry
from ml.feedback import AndroidFeedbackCollector, PriorityQueue, UncertaintySampler
from ml.workflow import EndToEndWorkflow, WorkflowConfig

# Initialize Flask app
app = Flask(__name__)
app.config['UPLOAD_FOLDER'] = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'static', 'uploads')
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024  # 16MB max upload size

# Ensure upload directory exists
os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)

# Initialize utility classes
model_manager = ModelManager()
image_processor = ImageProcessor()
dataset_manager = DatasetManager()
PROJECT_ROOT = Path(parent_dir).resolve()
GUIDELINES_CONFIG_PATH = Path(parent_dir) / 'config' / 'annotation_guidelines.json'
GUIDELINES_DOC_PATH = Path(parent_dir) / 'docs' / 'annotation_guidelines.md'
orchestrator_manager = TrainingJobManager()
evaluation_manager = EvaluationManager()
packaging_registry = PackagingRegistry()

# Initialize feedback components
feedback_collector = AndroidFeedbackCollector()
priority_queue = PriorityQueue()
uncertainty_sampler = UncertaintySampler()

# Initialize end-to-end workflow
workflow_config = WorkflowConfig(
    auto_collect_feedback=True,
    enable_active_learning=True,
    enable_continuous_learning=True,
    auto_release=False,  # Manual release by default
    enable_monitoring=True,
)
end_to_end_workflow = EndToEndWorkflow(workflow_config, PROJECT_ROOT)

@app.route('/')
def index():
    """Render the main page"""
    return render_template('index.html')

@app.route('/training')
def training():
    """Render the training interface"""
    return render_template('training.html')

@app.route('/testing')
def testing():
    """Render the testing interface"""
    return render_template('testing.html')

@app.route('/train-from-url', methods=['GET', 'POST'])
def train_from_url():
    """Render the train from URL interface"""
    return render_template('train_from_url.html')

@app.route('/api/upload/training', methods=['POST'])
def upload_training_images():
    """Handle training image uploads"""
    print("Received training image upload request")

    if 'files[]' not in request.files:
        print("No files found in request")
        return jsonify({'error': 'No files provided'}), 400

    files = request.files.getlist('files[]')
    print(f"Received {len(files)} files")

    uploaded_files = []

    for file in files:
        if file and allowed_file(file.filename):
            print(f"Processing file: {file.filename}")

            # Generate a unique filename
            filename = str(uuid.uuid4()) + os.path.splitext(file.filename)[1]
            filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            file.save(filepath)
            print(f"Saved file to: {filepath}")

            # Process the image (resize, normalize)
            processed_path = image_processor.preprocess_image(filepath)
            print(f"Processed image path: {processed_path}")

            file_url = url_for('static', filename=f'uploads/{filename}')
            print(f"File URL: {file_url}")

            uploaded_files.append({
                'original_name': file.filename,
                'saved_name': filename,
                'path': processed_path,
                'url': file_url
            })
        else:
            print(f"Invalid file: {file.filename if file else 'None'}")

    print(f"Returning {len(uploaded_files)} uploaded files")
    return jsonify({'files': uploaded_files})

@app.route('/api/upload/testing', methods=['POST'])
def upload_testing_images():
    """Handle testing image uploads"""
    if 'file' not in request.files:
        return jsonify({'error': 'No file provided'}), 400

    file = request.files['file']
    if file and allowed_file(file.filename):
        # Generate a unique filename
        filename = str(uuid.uuid4()) + os.path.splitext(file.filename)[1]
        filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
        file.save(filepath)

        try:
            # Process the image
            processed_path = image_processor.preprocess_image(filepath)

            # Check if the processed image exists
            if not os.path.exists(processed_path):
                print(f"Processed image not found: {processed_path}, using original")
                processed_path = filepath

            # Run pattern recognition
            results = model_manager.test_image(processed_path)
        except Exception as e:
            print(f"Error processing image: {e}")
            # If processing fails, try using the original image
            results = model_manager.test_image(filepath)

        return jsonify({
            'file': {
                'original_name': file.filename,
                'saved_name': filename,
                'url': url_for('static', filename=f'uploads/{filename}')
            },
            'results': results
        })

    return jsonify({'error': 'Invalid file'}), 400

@app.route('/api/annotate', methods=['POST'])
def save_annotation():
    """Save annotation data for an image"""
    data = request.json
    if not data or 'image' not in data or 'annotations' not in data:
        return jsonify({'error': 'Invalid annotation data'}), 400

    image_path = data['image']
    annotations = data['annotations']
    metadata = data.get('metadata')

    # Save annotations
    success = model_manager.save_annotations(image_path, annotations, metadata=metadata)

    if success:
        return jsonify({'status': 'success'})
    else:
        return jsonify({'error': 'Failed to save annotations'}), 500


@app.route('/api/annotation', methods=['GET'])
def get_annotation():
    image_path = request.args.get('image_path')
    if not image_path:
        return jsonify({'error': 'image_path is required'}), 400

    annotation_filename = os.path.splitext(os.path.basename(image_path))[0] + '.json'
    annotation_path = os.path.join(model_manager.data_dir, 'annotated', annotation_filename)

    if not os.path.exists(annotation_path):
        return jsonify({'annotation': None}), 200

    try:
        with open(annotation_path, 'r') as f:
            payload = json.load(f)
    except json.JSONDecodeError:
        return jsonify({'error': 'Malformed annotation file'}), 500

    return jsonify({'annotation': payload})

@app.route('/api/train', methods=['POST'])
def train_model():
    """Train the model using annotated images"""
    # Start training process
    result = model_manager.train_model()

    if result['success']:
        return jsonify({
            'status': 'success',
            'message': 'Model trained successfully',
            'model_info': result['model_info']
        })
    else:
        return jsonify({
            'status': 'error',
            'message': result['error']
        }), 500

@app.route('/api/update-app', methods=['POST'])
def update_app_model():
    """Update the model in the Android app"""
    result = model_manager.update_app_model()

    if result['success']:
        return jsonify({
            'status': 'success',
            'message': 'App model updated successfully',
            'details': result['details']
        })
    else:
        return jsonify({
            'status': 'error',
            'message': result['error']
        }), 500


@app.route('/api/datasets', methods=['GET', 'POST'])
def datasets():
    """List existing dataset versions or create a new one."""
    if request.method == 'GET':
        summaries = [summary.__dict__ for summary in dataset_manager.list_versions()]
        return jsonify({'datasets': summaries})

    payload = request.json or {}
    description = payload.get('description', '')
    tags = payload.get('tags') or []
    train_ratio = float(payload.get('train_ratio', 0.7))
    val_ratio = float(payload.get('val_ratio', 0.15))
    seed = int(payload.get('seed', 42))

    try:
        summary = dataset_manager.create_dataset(
            description=description,
            tags=tags,
            train_ratio=train_ratio,
            val_ratio=val_ratio,
            seed=seed,
        )
    except Exception as exc:  # pragma: no cover - surfaces in UI log
        return jsonify({'error': str(exc)}), 500

    return jsonify({'dataset': summary.__dict__}), 201


@app.route('/api/datasets/<version>', methods=['GET'])
def dataset_info(version):
    summary = dataset_manager.get_version(version)
    if not summary:
        return jsonify({'error': 'Dataset version not found'}), 404
    return jsonify({'dataset': summary.__dict__})


@app.route('/api/datasets/<version>/manifest', methods=['GET'])
def dataset_manifest(version):
    summary = dataset_manager.get_version(version)
    if not summary:
        return jsonify({'error': 'Dataset version not found'}), 404
    manifest_path = Path(summary.manifest_path)
    if not manifest_path.exists():
        return jsonify({'error': 'Manifest file missing'}), 404
    return send_file(manifest_path, as_attachment=True, download_name=f'{version}_manifest.json')


@app.route('/api/annotation-guidelines', methods=['GET'])
def annotation_guidelines():
    config = {}
    markdown = ''

    if GUIDELINES_CONFIG_PATH.exists():
        try:
            config = json.loads(GUIDELINES_CONFIG_PATH.read_text())
        except json.JSONDecodeError:
            config = {}

    if GUIDELINES_DOC_PATH.exists():
        markdown = GUIDELINES_DOC_PATH.read_text()

    return jsonify({'config': config, 'markdown': markdown})


@app.route('/api/preannotation', methods=['POST'])
def preannotation():
    payload = request.json or {}
    image_path = payload.get('image_path')
    if not image_path:
        return jsonify({'error': 'image_path is required'}), 400

    candidate_path = Path(image_path)
    if not candidate_path.is_absolute():
        candidate_path = (PROJECT_ROOT / candidate_path).resolve()

    try:
        candidate_path.relative_to(PROJECT_ROOT)
    except ValueError:
        return jsonify({'error': 'Invalid image path'}), 400

    if not candidate_path.exists():
        return jsonify({'error': f'Image not found: {candidate_path}'}), 404

    suggestions = [cand.__dict__ for cand in generate_candidates(candidate_path)]
    return jsonify({'image_path': str(candidate_path), 'candidates': suggestions})


@app.route('/api/orchestrator/jobs', methods=['GET'])
def orchestrator_jobs_list():
    jobs = orchestrator_manager.list_jobs()
    return jsonify({'jobs': [job.__dict__ for job in jobs.values()]})


@app.route('/api/orchestrator/jobs', methods=['POST'])
def orchestrator_jobs_create():
    payload = request.json or {}
    try:
        request_obj = JobRequestSchema(**payload)
        job = orchestrator_manager.submit(request_obj)
    except ValidationError as exc:
        return jsonify({'error': exc.errors()}), 400
    except Exception as exc:
        return jsonify({'error': str(exc)}), 500
    return jsonify({'job': job.__dict__}), 201


@app.route('/api/orchestrator/jobs/<job_id>', methods=['GET'])
def orchestrator_job_detail(job_id):
    job = orchestrator_manager.get_job(job_id)
    if not job:
        return jsonify({'error': 'Job not found'}), 404
    return jsonify({'job': job.__dict__})


@app.route('/api/evaluations', methods=['GET'])
def evaluations_list():
    evaluations = evaluation_manager.list_evaluations()
    return jsonify({'evaluations': [record.__dict__ for record in evaluations]})


@app.route('/api/evaluations/<path:run_path>', methods=['GET'])
def evaluations_detail(run_path):
    result = evaluation_manager.get_evaluation(run_path)
    if not result:
        return jsonify({'error': 'Evaluation not found'}), 404
    result['run_path'] = run_path
    return jsonify(result)


@app.route('/api/packages', methods=['GET'])
def packages_list():
    records = packaging_registry.list()
    return jsonify({'packages': [record.__dict__ for record in records]})


@app.route('/api/packages/<package_id>', methods=['GET'])
def packages_detail(package_id):
    record = packaging_registry.find(package_id)
    if not record:
        return jsonify({'error': 'Package not found'}), 404
    return jsonify({'package': record.__dict__})


@app.route('/api/packages/<package_id>/artifact/<format_name>', methods=['GET'])
def packages_download(package_id, format_name):
    artifact = packaging_registry.find_artifact(package_id, format_name)
    if not artifact:
        return jsonify({'error': 'Artifact not found'}), 404
    path = Path(artifact.get('path', ''))
    if not path.exists():
        return jsonify({'error': 'File missing on disk'}), 404
    return send_file(path, as_attachment=True, download_name=path.name)

@app.route('/api/models', methods=['GET'])
def get_models():
    """Get list of available models"""
    models = model_manager.get_models()
    return jsonify({'models': models})

@app.route('/api/model-metrics', methods=['GET'])
def get_model_metrics():
    """API endpoint to get the model metrics."""
    try:
        # Check if a specific version is requested
        version = request.args.get('version', 'latest')

        # Get model metrics from model manager
        metrics = model_manager.get_model_metrics(version)

        if metrics:
            return jsonify(metrics)

        # Return a message indicating no metrics are available
        return jsonify({
            'model_version': version,
            'message': 'No metrics data available for this model version',
            'no_data': True
        })
    except Exception as e:
        print(f"Error getting model metrics: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/training-sessions', methods=['GET'])
def get_training_sessions():
    """API endpoint to get a list of all training sessions."""
    try:
        # Get all training sessions from model manager
        sessions = model_manager.get_training_sessions()
        return jsonify({'sessions': sessions})
    except Exception as e:
        print(f"Error getting training sessions: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/train-from-url', methods=['POST'])
def api_train_from_url():
    """API endpoint to train the model from a URL."""
    try:
        data = request.json
        if not data or 'url' not in data:
            return jsonify({'error': 'URL is required'}), 400

        url = data['url']
        filter_images = data.get('filter', True)
        augment_images = data.get('augment', True)
        update_app = data.get('update_app', False)

        # Start the training process in a background thread
        task_id = model_manager.train_from_url(
            url,
            filter_images=filter_images,
            augment_images=augment_images,
            update_app=update_app
        )

        return jsonify({
            'status': 'success',
            'message': 'Training process started',
            'task_id': task_id
        })
    except Exception as e:
        print(f"Error training from URL: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/train-from-url/status/<task_id>', methods=['GET'])
def get_train_from_url_status(task_id):
    """API endpoint to get the status of a URL training task."""
    try:
        status = model_manager.get_url_training_status(task_id)
        return jsonify(status)
    except Exception as e:
        print(f"Error getting training status: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/feedback/upload', methods=['POST'])
def upload_feedback():
    """API endpoint to upload failure feedback from Android app."""
    try:
        # Get form data
        session_id = request.form.get('session_id', str(uuid.uuid4()))
        app_version = request.form.get('app_version', 'unknown')
        model_version = request.form.get('model_version', 'unknown')
        failure_type = request.form.get('failure_type', 'unknown')
        confidence_scores = json.loads(request.form.get('confidence_scores', '{}'))
        detected_regions = json.loads(request.form.get('detected_regions', '[]'))
        user_corrections = request.form.get('user_corrections')
        device_info = request.form.get('device_info')
        error_message = request.form.get('error_message')
        
        # Parse JSON fields
        if user_corrections:
            user_corrections = json.loads(user_corrections)
        if device_info:
            device_info = json.loads(device_info)
        
        # Get uploaded image
        if 'image' not in request.files:
            return jsonify({'error': 'No image provided'}), 400
        
        image_file = request.files['image']
        if image_file.filename == '':
            return jsonify({'error': 'No image selected'}), 400
        
        # Save image
        image_filename = f"{session_id}_{int(datetime.utcnow().timestamp())}.jpg"
        image_path = Path(app.config['UPLOAD_FOLDER']) / 'feedback' / image_filename
        image_path.parent.mkdir(parents=True, exist_ok=True)
        image_file.save(str(image_path))
        
        # Add to priority queue based on failure type
        priority_score = 0.8  # Default high priority for user feedback
        if failure_type == 'user_correction':
            priority_score = 1.0  # Highest priority
        elif failure_type == 'parsing_failed':
            priority_score = 0.9
        elif failure_type == 'ocr_failed':
            priority_score = 0.7
        elif failure_type == 'detection_failed':
            priority_score = 0.6
        
        priority_queue.add_item(
            image_path=str(image_path),
            priority_score=priority_score,
            reason=failure_type,
            metadata={
                'session_id': session_id,
                'app_version': app_version,
                'model_version': model_version,
                'confidence_scores': confidence_scores,
                'detected_regions': detected_regions,
                'user_corrections': user_corrections,
                'device_info': device_info,
                'error_message': error_message,
            }
        )
        
        return jsonify({
            'status': 'success',
            'message': 'Feedback uploaded successfully',
            'session_id': session_id,
            'priority_score': priority_score
        })
        
    except Exception as e:
        print(f"Error uploading feedback: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/feedback/queue', methods=['GET'])
def get_feedback_queue():
    """API endpoint to get the current feedback queue."""
    try:
        limit = request.args.get('limit', 20, type=int)
        items = priority_queue.get_top_items(limit)
        
        return jsonify({
            'items': [
                {
                    'image_path': item.image_path,
                    'priority_score': item.priority_score,
                    'reason': item.reason,
                    'created_at': item.created_at,
                    'metadata': item.metadata,
                }
                for item in items
            ],
            'stats': priority_queue.get_stats()
        })
    except Exception as e:
        print(f"Error getting feedback queue: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/feedback/process/<path:image_path>', methods=['POST'])
def mark_feedback_processed(image_path):
    """API endpoint to mark a feedback item as processed."""
    try:
        priority_queue.mark_processed(image_path)
        return jsonify({'status': 'success', 'message': 'Item marked as processed'})
    except Exception as e:
        print(f"Error marking feedback as processed: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/active-learning/uncertainty', methods=['POST'])
def calculate_uncertainty():
    """API endpoint to calculate uncertainty for active learning."""
    try:
        data = request.json
        if not data or 'predictions' not in data:
            return jsonify({'error': 'Predictions data required'}), 400
        
        predictions = data['predictions']
        top_k = data.get('top_k', 20)
        min_uncertainty = data.get('min_uncertainty', 0.3)
        
        # Calculate uncertain samples
        samples = uncertainty_sampler.sample_uncertain_images(
            predictions, top_k, min_uncertainty
        )
        
        # Add to priority queue
        for sample in samples:
            priority_queue.add_item(
                image_path=sample.image_path,
                priority_score=sample.uncertainty_score,
                reason='uncertainty',
                metadata={
                    'uncertainty_score': sample.uncertainty_score,
                    'confidence_scores': sample.confidence_scores,
                    'predicted_regions': sample.predicted_regions,
                    'metadata': sample.metadata,
                }
            )
        
        return jsonify({
            'status': 'success',
            'uncertain_samples': len(samples),
            'added_to_queue': len(samples)
        })
        
    except Exception as e:
        print(f"Error calculating uncertainty: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/workflow/status', methods=['GET'])
def get_workflow_status():
    """API endpoint to get workflow status."""
    try:
        status = end_to_end_workflow.get_workflow_status()
        metrics = end_to_end_workflow.get_workflow_metrics()
        
        return jsonify({
            'status': {
                'is_running': status.is_running,
                'workflow_health': status.workflow_health,
                'last_feedback_collection': status.last_feedback_collection,
                'last_retrain': status.last_retrain,
                'last_release': status.last_release,
                'current_model_version': status.current_model_version,
                'created_at': status.created_at,
                'last_updated': status.last_updated,
            },
            'metrics': metrics
        })
    except Exception as e:
        print(f"Error getting workflow status: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/workflow/start', methods=['POST'])
def start_workflow():
    """API endpoint to start the end-to-end workflow."""
    try:
        end_to_end_workflow.start_workflow()
        return jsonify({'status': 'success', 'message': 'Workflow started'})
    except Exception as e:
        print(f"Error starting workflow: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/workflow/stop', methods=['POST'])
def stop_workflow():
    """API endpoint to stop the end-to-end workflow."""
    try:
        end_to_end_workflow.stop_workflow()
        return jsonify({'status': 'success', 'message': 'Workflow stopped'})
    except Exception as e:
        print(f"Error stopping workflow: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/workflow/retrain', methods=['POST'])
def trigger_manual_retrain():
    """API endpoint to manually trigger a retrain cycle."""
    try:
        retrain_id = end_to_end_workflow.trigger_manual_retrain()
        return jsonify({
            'status': 'success', 
            'message': 'Retrain triggered',
            'retrain_id': retrain_id
        })
    except Exception as e:
        print(f"Error triggering retrain: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/workflow/release', methods=['POST'])
def trigger_manual_release():
    """API endpoint to manually trigger a release."""
    try:
        data = request.json
        if not data or 'version' not in data:
            return jsonify({'error': 'Version is required'}), 400
        
        version = data['version']
        description = data.get('description', f'Manual release {version}')
        
        release_id = end_to_end_workflow.trigger_manual_release(version, description)
        return jsonify({
            'status': 'success',
            'message': 'Release triggered',
            'release_id': release_id
        })
    except Exception as e:
        print(f"Error triggering release: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/api/workflow/metrics', methods=['GET'])
def get_workflow_metrics():
    """API endpoint to get detailed workflow metrics."""
    try:
        metrics = end_to_end_workflow.get_workflow_metrics()
        
        # Add additional metrics
        queue_stats = priority_queue.get_stats()
        metrics['priority_queue_stats'] = queue_stats
        
        return jsonify(metrics)
    except Exception as e:
        print(f"Error getting workflow metrics: {e}")
        return jsonify({'error': str(e)}), 500

def allowed_file(filename):
    """Check if the file has an allowed extension"""
    ALLOWED_EXTENSIONS = {'png', 'jpg', 'jpeg', 'gif'}
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=8080)
