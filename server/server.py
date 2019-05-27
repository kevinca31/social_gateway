from flask import Flask, request
import random
from datetime import datetime

app = Flask(__name__)

questions = [
    'What do you expect of using <app_name> right now?',
    'question 2',
    'question 3']

app_names = [
    'this app',
    'Instagram',
    'Snapchat',
    'WhatsApp',
    'Facebook',
    'Facebook Messenger',
    'Signal',
    'Telegram'
]

@app.route('/question')
def send_question():
    app_id = int(request.args.get('app_id', '0'))
    return random.choices(questions)[0].replace('<app_name>', app_names[app_id])

@app.route('/answer')
def receive_answer():
    app_id = request.args.get('app_id', 'NULL')
    date = datetime.utcnow().isoformat()
    question_id = request.args.get('question_id', 'NULL')
    answer = request.args.get('answer', 'NULL')
    user_id = request.args.get('user_id', 'NULL')
    print(user_id, date, app_id, question_id, answer, sep=', ')
    return 'Thanks for your answer!'
