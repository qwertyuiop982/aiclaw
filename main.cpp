#include <QtWidgets>
#include <QtNetwork>

static QString configPath() { return QDir::homePath() + "/.aiclaw/config.json"; }
static QString sessionsPath() { return QDir::homePath() + "/.aiclaw/sessions"; }
static QString workspacePath() { return qEnvironmentVariable("AICLAW_WORKSPACE", QDir::currentPath()); }
static QString safePath(const QString &raw) { QFileInfo root(workspacePath()), candidate(raw.isEmpty() ? root.absoluteFilePath() : (QDir::isAbsolutePath(raw) ? raw : root.absoluteFilePath(raw))); QString r=root.canonicalFilePath(), c=candidate.canonicalFilePath(); if (r.isEmpty()) r=root.absoluteFilePath(); if (c.isEmpty()) c=candidate.absoluteFilePath(); return (c==r || c.startsWith(r+"/")) ? c : QString(); }

static QString toolsPrompt() {
    return "\n\n# Tool calling\nWhen tools are enabled, call a tool with a fenced JSON block:\n```tool_call\n{\"name\":\"list_dir|read_file|grep_search|shell\",\"arguments\":{...}}\n```\nAfter a result is returned, answer the user. Available tools: list_dir(path), read_file(path), grep_search(pattern,path), shell(command).";
}

class SettingsDialog : public QDialog {
public:
    QComboBox *profile;
    QLineEdit *url, *key, *model, *reasoning;
    QTextEdit *system;
    QComboBox *thinking;
    QCheckBox *tools, *toolPrompt;
    QJsonObject configs;

    SettingsDialog(const QJsonObject &root, const QString &current, QWidget *parent = nullptr) : QDialog(parent) {
        setWindowTitle("Settings"); resize(580, 460);
        configs = root["configs"].toObject();
        profile = new QComboBox;
        for (const QString &name : configs.keys()) profile->addItem(name);
        profile->setCurrentText(current);
        url = new QLineEdit; key = new QLineEdit; key->setEchoMode(QLineEdit::Password);
        model = new QLineEdit; reasoning = new QLineEdit; system = new QTextEdit; system->setMaximumHeight(110);
        thinking = new QComboBox; thinking->addItems({"default", "enabled", "disabled"});
        tools = new QCheckBox("Enable tool calling");
        toolPrompt = new QCheckBox("Inject tool prompt");
        toolPrompt->setToolTip("Controls only the tool protocol injected into the system message.");
        auto *form = new QFormLayout;
        form->addRow("Configuration", profile); form->addRow("API URL", url); form->addRow("API key", key);
        form->addRow("Model", model); form->addRow("Thinking", thinking); form->addRow("Reasoning effort", reasoning); form->addRow("System prompt", system);
        form->addRow(tools); form->addRow(toolPrompt);
        auto *save = new QPushButton("Save"), *cancel = new QPushButton("Cancel");
        auto *buttons = new QHBoxLayout; buttons->addStretch(); buttons->addWidget(cancel); buttons->addWidget(save);
        auto *layout = new QVBoxLayout(this); layout->addLayout(form); layout->addStretch(); layout->addLayout(buttons);
        connect(profile, &QComboBox::currentTextChanged, this, [this](const QString &name) { load(configs[name].toObject()); });
        connect(tools, &QCheckBox::toggled, toolPrompt, &QWidget::setEnabled);
        connect(save, &QPushButton::clicked, this, &QDialog::accept); connect(cancel, &QPushButton::clicked, this, &QDialog::reject);
        load(configs[current].toObject());
    }
    void load(const QJsonObject &c) {
        url->setText(c["baseURL"].toString()); key->setText(c["apiKey"].toString()); model->setText(c["model"].toString());
        system->setPlainText(c["system"].toString()); thinking->setCurrentText(c["thinking"].toString().isEmpty() ? "default" : c["thinking"].toString()); reasoning->setText(c["reasoning_effort"].toString());
        tools->setChecked(c["toolsEnabled"].toBool(false)); toolPrompt->setChecked(c["toolPromptEnabled"].toBool(false)); toolPrompt->setEnabled(tools->isChecked());
    }
    QString selectedName() const { return profile->currentText(); }
    QJsonObject value() const {
        QJsonObject c; c["baseURL"] = url->text().trimmed(); c["apiKey"] = key->text(); c["model"] = model->text().trimmed();
        c["system"] = system->toPlainText(); c["thinking"] = thinking->currentText() == "default" ? "" : thinking->currentText(); c["reasoning_effort"] = reasoning->text().trimmed();
        c["toolsEnabled"] = tools->isChecked(); c["toolPromptEnabled"] = tools->isChecked() && toolPrompt->isChecked(); return c;
    }
};

class ChatBubble : public QFrame {
public:
    ChatBubble(const QString &speaker, const QString &content, const QString &reasoning, bool user, QWidget *parent = nullptr) : QFrame(parent) {
        setObjectName(user ? "userBubble" : "assistantBubble"); setMaximumWidth(700);
        auto *layout = new QVBoxLayout(this); layout->setContentsMargins(12, 8, 12, 8);
        auto *name = new QLabel(speaker); name->setStyleSheet("font-weight:600;"); layout->addWidget(name);
        auto *body = new QLabel(content.toHtmlEscaped().replace("\n", "<br>")); body->setWordWrap(true); body->setTextInteractionFlags(Qt::TextSelectableByMouse); layout->addWidget(body);
        if (!reasoning.isEmpty()) {
            auto *toggle = new QToolButton; toggle->setText("Thinking  >"); toggle->setCheckable(true); toggle->setToolButtonStyle(Qt::ToolButtonTextOnly);
            auto *details = new QTextEdit; details->setPlainText(reasoning); details->setReadOnly(true); details->setVisible(false); details->setMaximumHeight(180);
            layout->addWidget(toggle); layout->addWidget(details);
            connect(toggle, &QToolButton::toggled, details, &QWidget::setVisible);
            connect(toggle, &QToolButton::toggled, toggle, [toggle](bool open) { toggle->setText(open ? "Thinking  v" : "Thinking  >"); });
        }
    }
};

class MainWindow : public QMainWindow {
    QJsonObject root, cfg; QString current, activeSession;
    QListWidget *sessionList; QScrollArea *scroll; QWidget *messages; QVBoxLayout *messageLayout;
    QTextEdit *input; QPushButton *send; QLabel *title; QNetworkAccessManager network;
    QJsonArray history; int requestStep = 0;
public:
    MainWindow() { loadConfig(); buildUi(); loadSessions(); }
private:
    void loadConfig() {
        QFile file(configPath()); if (file.open(QIODevice::ReadOnly)) root = QJsonDocument::fromJson(file.readAll()).object();
        current = root["current"].toString(); cfg = root["configs"].toObject()[current].toObject(); activeSession = root["currentSession"].toString("default");
    }
    void saveConfig() {
        QJsonObject configs = root["configs"].toObject(); configs[current] = cfg; root["configs"] = configs; root["current"] = current; root["currentSession"] = activeSession;
        QDir().mkpath(QDir::homePath() + "/.aiclaw"); QFile file(configPath()); if (file.open(QIODevice::WriteOnly)) file.write(QJsonDocument(root).toJson(QJsonDocument::Indented));
    }
    QString sessionFile(const QString &name) const { return sessionsPath() + "/" + name + ".jsonl"; }
    void appendSession(const QJsonObject &msg) { QDir().mkpath(sessionsPath()); QFile file(sessionFile(activeSession)); if (file.open(QIODevice::Append)) file.write(QJsonDocument(msg).toJson(QJsonDocument::Compact) + "\n"); }
    void addBubble(const QString &speaker, const QString &content, const QString &reasoning, bool user) {
        auto *row = new QHBoxLayout; row->setContentsMargins(8, 4, 8, 4); auto *bubble = new ChatBubble(speaker, content, reasoning, user);
        if (user) { row->addStretch(); row->addWidget(bubble); } else { row->addWidget(bubble); row->addStretch(); }
        auto *container = new QWidget; container->setLayout(row); messageLayout->insertWidget(messageLayout->count() - 1, container);
        QTimer::singleShot(0, this, [this] { scroll->verticalScrollBar()->setValue(scroll->verticalScrollBar()->maximum()); });
    }
    void clearChat() { while (messageLayout->count() > 1) { auto *item = messageLayout->takeAt(0); delete item->widget(); delete item; } }
    void loadSession(const QString &name) {
        activeSession = name; history = QJsonArray(); clearChat(); QFile file(sessionFile(name));
        if (file.open(QIODevice::ReadOnly)) while (!file.atEnd()) { QJsonObject msg = QJsonDocument::fromJson(file.readLine()).object(); QString role = msg["role"].toString(); if (role == "user" || role == "assistant") { history.append(QJsonObject{{"role", role}, {"content", msg["content"].toString()}}); addBubble(role == "user" ? "You" : "AI", msg["content"].toString(), msg["reasoning"].toString(), role == "user"); } }
        saveConfig();
    }
    void loadSessions() {
        QDir().mkpath(sessionsPath()); sessionList->clear(); QStringList names = QDir(sessionsPath()).entryList({"*.jsonl"}, QDir::Files, QDir::Time); if (!names.contains(activeSession + ".jsonl")) names.prepend(activeSession + ".jsonl");
        for (QString name : names) sessionList->addItem(name.chopped(6)); sessionList->addItem("+ New conversation");
        loadSession(activeSession);
    }
    void buildUi() {
        setWindowTitle("aiclaw"); resize(1080, 760);
        setStyleSheet("QMainWindow{background:#f4f6f8} QListWidget,QTextEdit,QLineEdit,QComboBox{background:#fff;border:1px solid #d5dbe3;border-radius:6px;padding:6px} QPushButton{background:#2563eb;color:#fff;border:0;border-radius:6px;padding:9px 16px} QPushButton:disabled{background:#a4acb8} #userBubble{background:#dbeafe;border-radius:9px} #assistantBubble{background:#fff;border:1px solid #dde3ea;border-radius:9px}");
        auto *split = new QSplitter; auto *side = new QWidget; side->setFixedWidth(205); auto *sideLayout = new QVBoxLayout(side); sessionList = new QListWidget; auto *settings = new QPushButton("Settings"); sideLayout->addWidget(new QLabel("Conversations")); sideLayout->addWidget(sessionList); sideLayout->addWidget(settings); split->addWidget(side);
        auto *center = new QWidget; auto *layout = new QVBoxLayout(center); title = new QLabel; title->setStyleSheet("font-size:18px;font-weight:600;padding:8px"); layout->addWidget(title);
        messages = new QWidget; messageLayout = new QVBoxLayout(messages); messageLayout->addStretch(); scroll = new QScrollArea; scroll->setWidgetResizable(true); scroll->setWidget(messages); scroll->setFrameShape(QFrame::NoFrame); layout->addWidget(scroll, 1);
        input = new QTextEdit; input->setPlaceholderText("Message"); input->setMaximumHeight(100); send = new QPushButton("Send"); send->setEnabled(false); auto *row = new QHBoxLayout; row->addWidget(input, 1); row->addWidget(send); layout->addLayout(row); split->addWidget(center); split->setStretchFactor(1, 1); setCentralWidget(split); refreshTitle();
        connect(input, &QTextEdit::textChanged, this, [this] { send->setEnabled(!input->toPlainText().trimmed().isEmpty()); }); connect(send, &QPushButton::clicked, this, &MainWindow::sendMessage);
        connect(sessionList, &QListWidget::itemClicked, this, [this](QListWidgetItem *item) { QString name = item->text(); if (name == "+ New conversation") { bool ok = false; name = QInputDialog::getText(this, "New conversation", "Name", QLineEdit::Normal, "", &ok).trimmed(); if (!ok || name.isEmpty() || !QRegularExpression("^[\\w.-]+$").match(name).hasMatch()) return; activeSession = name; QFile(sessionFile(name)).open(QIODevice::WriteOnly); loadSessions(); } else loadSession(name); });
        connect(settings, &QPushButton::clicked, this, [this] { SettingsDialog dialog(root, current, this); if (dialog.exec() == QDialog::Accepted) { QString old = current; current = dialog.selectedName(); QJsonObject configs = root["configs"].toObject(); configs[current] = dialog.value(); root["configs"] = configs; cfg = configs[current].toObject(); if (old != current) { history = QJsonArray(); clearChat(); } saveConfig(); refreshTitle(); } });
    }
    void refreshTitle() { title->setText("aiclaw  |  " + current + " / " + cfg["model"].toString() + (cfg["toolsEnabled"].toBool() ? "  | tools on" : "")); }
    QJsonArray apiMessages() const {
        QJsonArray out = history; QString system = cfg["system"].toString(); if (cfg["toolsEnabled"].toBool() && cfg["toolPromptEnabled"].toBool()) system += toolsPrompt(); if (cfg["toolsEnabled"].toBool()) system += "\nWhen reasoning, wrap it in <think> and </think>.";
        if (!system.trimmed().isEmpty()) out.prepend(QJsonObject{{"role", "system"}, {"content", system}}); return out;
    }
    QString runTool(const QString &name, const QJsonObject &args) {
        if (name == "list_dir") { QString p=safePath(args["path"].toString()); if(p.isEmpty()) return "ERROR: path outside workspace"; QDir dir(p); return dir.entryList(QDir::AllEntries | QDir::NoDotAndDotDot).join("\n"); }
        if (name == "read_file") { QString p=safePath(args["path"].toString()); if(p.isEmpty()) return "ERROR: path outside workspace"; QFile file(p); if (!file.open(QIODevice::ReadOnly)) return "ERROR: " + file.errorString(); return QString::fromUtf8(file.read(200000)); }
        if (name == "grep_search") { QString safe=safePath(args["path"].toString()); if(safe.isEmpty()) return "ERROR: path outside workspace"; QProcess p; p.start("grep", {"-rEn", args["pattern"].toString(), safe}); p.waitForFinished(20000); return QString::fromUtf8(p.readAllStandardOutput()).left(200000); }
        if (name == "shell") { QStringList parts=args["command"].toString().split(QRegularExpression("\\s+"), Qt::SkipEmptyParts); if(parts.isEmpty()) return "ERROR: empty command"; static const QStringList allow={"pwd","ls","find","grep","cat","head","tail","wc","git","node","npm","cmake","make","ninja","clang","clang++","qmake","python","python3","echo","printf","sed","sort","du","file"}; if(!allow.contains(QFileInfo(parts[0]).fileName())) return "ERROR: command not allowed"; QProcess p; p.setWorkingDirectory(workspacePath()); p.start(parts.takeFirst(), parts); if(!p.waitForFinished(15000)) { p.kill(); return "ERROR: command timeout"; } return QString::fromUtf8(p.readAllStandardOutput()+p.readAllStandardError()).left(200000); }
        return "ERROR: unknown tool " + name;
    }
    bool executeToolCall(const QString &text) {
        QRegularExpression re("```tool_call\\s*\\n?([\\s\\S]*?)```"); auto match = re.match(text); if (!match.hasMatch()) return false;
        QJsonDocument doc = QJsonDocument::fromJson(match.captured(1).trimmed().toUtf8()); QJsonObject call = doc.object(); if (call.isEmpty()) return false;
        QString name = call["name"].toString(); QString output = runTool(name, call["arguments"].toObject()); history.append(QJsonObject{{"role", "assistant"}, {"content", text}}); history.append(QJsonObject{{"role", "user"}, {"content", "[Tool Result: " + name + "]\n" + output}}); requestStep++; requestCompletion(); return true;
    }
    void sendMessage() {
        QString text = input->toPlainText().trimmed(); if (text.isEmpty()) return; input->clear(); history.append(QJsonObject{{"role", "user"}, {"content", text}}); appendSession(QJsonObject{{"role", "user"}, {"content", text}}); addBubble("You", text, "", true); requestStep = 0; requestCompletion();
    }
    void requestCompletion() {
        QJsonObject body{{"model", cfg["model"].toString()}, {"messages", apiMessages()}, {"stream", false}}; QString thinking = cfg["thinking"].toString(); if (!thinking.isEmpty()) body["thinking"] = QJsonObject{{"type", thinking}}; QString effort=cfg["reasoning_effort"].toString(); if(!effort.isEmpty()) body["reasoning_effort"]=effort;
        QNetworkRequest request(QUrl(cfg["baseURL"].toString())); request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json"); request.setRawHeader("Authorization", ("Bearer " + cfg["apiKey"].toString()).toUtf8()); send->setEnabled(false); send->setText("Working...");
        QNetworkReply *reply = network.post(request, QJsonDocument(body).toJson()); connect(reply, &QNetworkReply::finished, this, [this, reply] {
            send->setText("Send"); send->setEnabled(!input->toPlainText().trimmed().isEmpty()); if (reply->error() != QNetworkReply::NoError) { addBubble("Error", reply->errorString(), "", false); reply->deleteLater(); return; }
            QJsonArray choices = QJsonDocument::fromJson(reply->readAll()).object()["choices"].toArray(); if (choices.isEmpty()) { addBubble("Error", "The API response has no choices.", "", false); reply->deleteLater(); return; }
            QJsonObject message = choices.at(0).toObject()["message"].toObject(); QString answer = message["content"].toString(); QString reasoning = message["reasoning_content"].toString(); int a = answer.indexOf("<think>"), b = answer.indexOf("</think>"); if (a >= 0 && b > a) { reasoning = answer.mid(a + 7, b - a - 7); answer.remove(a, b + 8 - a); }
            if (cfg["toolsEnabled"].toBool() && requestStep < 6 && executeToolCall(answer)) { reply->deleteLater(); return; }
            history.append(QJsonObject{{"role", "assistant"}, {"content", answer}}); appendSession(QJsonObject{{"role", "assistant"}, {"content", answer}, {"reasoning", reasoning}}); addBubble("AI", answer, reasoning, false); reply->deleteLater();
        });
    }
};

int main(int argc, char **argv) { QApplication app(argc, argv); MainWindow window; window.show(); return app.exec(); }