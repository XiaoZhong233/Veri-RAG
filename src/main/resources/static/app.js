const API_BASE = document.querySelector('meta[name="api-base"]').content.replace(/\/$/, '');
const state = {
    token: localStorage.getItem('veri-rag-token'), user: null, view: 'chat',
    userPage: 1, userSize: 10, userTotal: 0,
    documentPage: 1, documentSize: 10, documentTotal: 0,
    residencePage: 1, residenceSize: 20, residenceTotal: 0,
    offerPage: 1, offerSize: 20, offerTotal: 0, residenceOptions: [],
    categories: [], activeSessionId: null, selectedDocumentIds: new Set()
};
const $ = (selector) => document.querySelector(selector);

async function request(path, options = {}) {
    const isFormData = options.body instanceof FormData;
    const headers = { ...(options.body && !isFormData ? {'Content-Type': 'application/json'} : {}), ...(options.headers || {}) };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    const response = await fetch(`${API_BASE}${path}`, {...options, headers});
    const payload = await response.json().catch(() => ({code: response.status, message: '服务器返回了无效响应'}));
    if (!response.ok || payload.code !== 200) {
        if (response.status === 401 || payload.code === 401) logout(false);
        throw new Error(payload.message || '请求失败');
    }
    return payload.data;
}

async function streamRequest(path, body, onEvent) {
    const response = await fetch(`${API_BASE}${path}`, {
        method: 'POST', headers: {'Content-Type': 'application/json', Authorization: `Bearer ${state.token}`}, body: JSON.stringify(body)
    });
    if (!response.ok || !response.body) {
        const payload = await response.json().catch(() => ({message: '流式请求失败'}));
        if (response.status === 401 || payload.code === 401) logout(false);
        throw new Error(payload.message || '流式请求失败');
    }
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
    while (true) {
        const {done, value} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done}).replace(/\r\n/g, '\n');
        let boundary;
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
            const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
            let event = 'message'; const data = [];
            frame.split('\n').forEach(line => { if (line.startsWith('event:')) event = line.slice(6).trim(); if (line.startsWith('data:')) data.push(line.slice(5).trimStart()); });
            if (data.length) onEvent(event, JSON.parse(data.join('\n')));
        }
        if (done) break;
    }
}

function escapeHtml(value = '') { const element = document.createElement('div'); element.textContent = String(value); return element.innerHTML; }
function formatDate(value) { return value ? new Intl.DateTimeFormat('zh-CN', {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(value)) : '-'; }
function formatDateOnly(value) { return value ? String(value).slice(0, 10) : '-'; }
function toLocalDateTimeInput(value) { return value ? String(value).slice(0, 16) : ''; }
function initials(user) { return (user.realName || user.username || '?').trim().slice(0, 1).toUpperCase(); }
function avatarUrl(path) { return `${API_BASE}/files/${path.split('/').map(encodeURIComponent).join('/')}`; }
function avatarMarkup(user, extraClass = '') { return user.avatar ? `<span class="avatar ${extraClass}"><img src="${avatarUrl(user.avatar)}" alt=""></span>` : `<span class="avatar ${extraClass}">${escapeHtml(initials(user))}</span>`; }
function showToast(message) { const toast = $('#toast'); toast.textContent = message; toast.classList.add('show'); clearTimeout(showToast.timer); showToast.timer = setTimeout(() => toast.classList.remove('show'), 2600); }
function isAdmin() { return state.user?.role === 'ADMIN'; }

function setLoginError(message = '') { $('#login-error').textContent = message; }
async function login(event) {
    event.preventDefault(); setLoginError();
    const button = $('#login-submit'); button.disabled = true; button.textContent = '正在登录…';
    try {
        const data = await request('/api/auth/login', {method: 'POST', body: JSON.stringify({username: $('#login-username').value.trim(), password: $('#login-password').value})});
        state.token = data.token; state.user = data.user;
        localStorage.setItem('veri-rag-token', data.token); localStorage.setItem('veri-rag-user', JSON.stringify(data.user));
        await showConsole();
    } catch (error) { setLoginError(error.message); }
    finally { button.disabled = false; button.textContent = '登录'; }
}
function logout(showMessage = true) {
    state.token = null; state.user = null; state.activeSessionId = null;
    localStorage.removeItem('veri-rag-token'); localStorage.removeItem('veri-rag-user');
    $('#console-view').classList.add('hidden'); $('#login-view').classList.remove('hidden'); $('#login-form').reset();
    if (showMessage) showToast('已退出登录');
}
async function showConsole() {
    state.user = state.user || JSON.parse(localStorage.getItem('veri-rag-user') || 'null');
    if (!state.user) return logout(false);
    $('#login-view').classList.add('hidden'); $('#console-view').classList.remove('hidden');
    document.querySelectorAll('.admin-only').forEach(item => item.classList.toggle('hidden', !isAdmin()));
    renderCurrentUser(); await Promise.all([refreshCurrentUser(), loadCategories(), loadSessions()]); showView('chat');
}
async function refreshCurrentUser() {
    try { state.user = await request('/api/users/me'); localStorage.setItem('veri-rag-user', JSON.stringify(state.user)); renderCurrentUser(); }
    catch (error) { if (state.token) showToast(error.message); }
}
function renderCurrentUser() {
    const avatar = $('#current-avatar'); avatar.replaceChildren(); avatar.textContent = initials(state.user);
    if (state.user.avatar) { const image = new Image(); image.src = avatarUrl(state.user.avatar); image.alt = ''; image.onerror = () => { avatar.replaceChildren(); avatar.textContent = initials(state.user); }; avatar.replaceChildren(image); }
    $('#current-user-name').textContent = `${state.user.realName || state.user.username} · ${state.user.role}`;
    $('#profile-real-name').value = state.user.realName || '';
}

const viewInfo = {chat: ['PROPERTY INTELLIGENCE HUB', '智能问答'], knowledge: ['KNOWLEDGE BASE', '知识库'], residences: ['PROPERTY DATA', '公寓地址'], offers: ['INVENTORY & PRICING', '房型库存'], recommendations: ['SALES PREFERENCE', '推荐管理'], users: ['ADMINISTRATION', '用户管理'], profile: ['ACCOUNT', '个人设置']};
function showView(view) {
    if ((view === 'users' || view === 'recommendations') && !isAdmin()) { showToast('没有管理权限'); return; }
    state.view = view;
    Object.keys(viewInfo).forEach(name => $(`#${name}-view`).classList.toggle('hidden', name !== view));
    document.querySelectorAll('.nav-item').forEach(item => item.classList.toggle('active', item.dataset.view === view));
    $('#view-eyebrow').textContent = viewInfo[view][0]; $('#view-title').textContent = viewInfo[view][1];
    if (view === 'users') loadUsers();
    if (view === 'knowledge') { renderCategories(); loadDocuments(); }
    if (view === 'residences') loadResidences();
    if (view === 'offers') loadOffers();
    if (view === 'recommendations') loadRecommendations();
}

async function loadCategories() {
    try { state.categories = await request('/api/categories'); renderCategories(); renderCategoryInputs(); }
    catch (error) { showToast(error.message); }
}
function renderCategoryInputs() {
    const optionHtml = state.categories.map(c => `<option value="${c.id}">${escapeHtml(c.icon || '▤')} ${escapeHtml(c.name)}</option>`).join('');
    $('#document-category').innerHTML = `<option value="">全部分类</option>${optionHtml}`;
    $('#upload-category').innerHTML = `<option value="">请选择分类</option>${optionHtml}`;
    $('#chat-category-filter').innerHTML = `<button class="filter-chip active" data-category="" type="button">全部知识库</button>${state.categories.map(c => `<button class="filter-chip" data-category="${c.id}" type="button">${escapeHtml(c.icon || '▤')} ${escapeHtml(c.name)}</button>`).join('')}`;
}
function renderCategories() {
    const list = $('#category-list');
    list.innerHTML = state.categories.length ? state.categories.map(c => `<article class="category-card"><span class="category-icon">${escapeHtml(c.icon || '▤')}</span><div><strong>${escapeHtml(c.name)}</strong><p>${escapeHtml(c.description || '暂无描述')}</p></div>${isAdmin() ? `<button class="text-button danger delete-category" data-id="${c.id}" type="button">删除</button>` : ''}</article>`).join('') : '<p class="empty-state">还没有知识分类。</p>';
}
async function saveCategory(event) {
    event.preventDefault(); $('#category-error').textContent = '';
    const payload = {name: $('#category-name').value.trim(), description: $('#category-description').value.trim(), icon: $('#category-icon').value.trim(), sortOrder: Number($('#category-sort-order').value || 0)};
    try { await request('/api/categories', {method: 'POST', body: JSON.stringify(payload)}); $('#category-dialog').close(); event.target.reset(); showToast('分类已保存'); loadCategories(); }
    catch (error) { $('#category-error').textContent = error.message; }
}

async function loadDocuments() {
    if (state.view !== 'knowledge') return;
    try {
        const query = new URLSearchParams({keyword: $('#document-keyword').value.trim(), page: state.documentPage, size: state.documentSize});
        if ($('#document-category').value) query.set('categoryId', $('#document-category').value);
        const result = await request(`/api/documents/page?${query}`); state.documentTotal = result.total; renderDocuments(result.records);
        const pages = Math.max(Math.ceil(result.total / state.documentSize), 1); $('#document-pagination-info').textContent = `共 ${result.total} 个文档`; $('#document-page-number').textContent = `${state.documentPage} / ${pages}`;
        $('#document-prev').disabled = state.documentPage <= 1; $('#document-next').disabled = state.documentPage >= pages;
    } catch (error) { showToast(error.message); }
}
function renderDocuments(records) {
    const categories = new Map(state.categories.map(c => [c.id, c.name]));
    $('#document-list').innerHTML = records.length ? records.map(d => `<article class="document-row">${isAdmin() ? `<label class="document-selector"><input class="document-select" data-id="${d.id}" type="checkbox" ${state.selectedDocumentIds.has(d.id) ? 'checked' : ''}><span class="sr-only">选择 ${escapeHtml(d.title)}</span></label>` : ''}<span class="file-badge">${escapeHtml((d.fileType || '?').toUpperCase())}</span><div class="document-main"><strong>${escapeHtml(d.title)}</strong><p>${escapeHtml(categories.get(d.categoryId) || '未分类')} · ${escapeHtml(d.fileName || '')} · ${d.vectorCount ?? 0} 个片段</p></div><span class="status-tag ${String(d.status).toLowerCase()}">${escapeHtml(d.status)}</span><span class="muted">${formatDate(d.createTime)}</span>${isAdmin() ? `<span class="document-actions"><button class="text-button reingest-document" data-id="${d.id}" data-title="${escapeHtml(d.title)}" type="button">重新向量化</button><button class="text-button danger delete-document" data-id="${d.id}" data-title="${escapeHtml(d.title)}" type="button">删除</button></span>` : ''}</article>`).join('') : '<p class="empty-state">没有找到文档。</p>';
    updateBatchReingestButton();
}

function updateBatchReingestButton() {
    const button = $('#batch-reingest-button');
    if (!button) return;
    const count = state.selectedDocumentIds.size;
    button.disabled = count === 0;
    button.textContent = `批量重新向量化（${count}）`;
}

async function batchReingestDocuments() {
    const ids = [...state.selectedDocumentIds];
    if (!ids.length || !confirm(`确定重新向量化已选择的 ${ids.length} 个文档吗？过程可能需要一些时间。`)) return;
    const button = $('#batch-reingest-button');
    button.disabled = true; button.textContent = '批量处理中…';
    try {
        const result = await request('/api/documents/reingest', {method: 'POST', body: JSON.stringify({documentIds: ids})});
        state.selectedDocumentIds.clear();
        showToast(result.failedCount ? `完成：成功 ${result.successCount}，失败 ${result.failedCount}` : `已完成 ${result.successCount} 个文档的重新向量化`);
        if (result.failedCount) console.warn('Batch re-vectorization failures:', result.items.filter(item => !item.success));
        await loadDocuments();
    } catch (error) {
        showToast(error.message);
        updateBatchReingestButton();
    }
}
async function uploadDocument(event) {
    event.preventDefault(); $('#document-error').textContent = '';
    const form = new FormData(); form.append('file', $('#document-file').files[0]); form.append('categoryId', $('#upload-category').value); if ($('#document-title').value.trim()) form.append('title', $('#document-title').value.trim());
    const button = $('#document-submit'); button.disabled = true; button.textContent = '正在上传…';
    try { await request('/api/documents', {method: 'POST', body: form}); $('#document-dialog').close(); event.target.reset(); showToast('文档已上传并完成向量化'); loadDocuments(); }
    catch (error) { $('#document-error').textContent = error.message; }
    finally { button.disabled = false; button.textContent = '上传并向量化'; }
}

async function loadSessions() {
    try {
        const sessions = await request('/api/chat/sessions'); const list = $('#session-list');
        list.innerHTML = sessions.length ? sessions.map(s => `<button class="session-item ${s.id === state.activeSessionId ? 'active' : ''}" data-id="${s.id}" type="button"><span>${escapeHtml(s.title || '未命名会话')}</span><small>${formatDate(s.createTime)}</small><i data-delete-session="${s.id}" title="删除会话">×</i></button>`).join('') : '<p class="session-empty">暂无历史会话</p>';
    } catch (error) { showToast(error.message); }
}
async function openSession(id) {
    state.activeSessionId = Number(id); $('#chat-empty').classList.add('hidden'); await loadSessions();
    try { const messages = await request(`/api/chat/sessions/${id}/messages`); renderMessages(messages); }
    catch (error) { showToast(error.message); }
}
function newChat() { state.activeSessionId = null; $('#message-list').replaceChildren(); $('#chat-empty').classList.remove('hidden'); loadSessions(); $('#chat-question').focus(); }
function renderMessages(messages) {
    const list = $('#message-list'); list.replaceChildren(); messages.forEach(message => appendMessage(message.role, message.content, parseRefs(message.refs))); list.scrollTop = list.scrollHeight;
}
function parseRefs(value) { try { return value ? JSON.parse(value) : []; } catch { return []; } }

/**
 * 将模型输出安全地渲染为一个有限的 Markdown 子集。
 * 不把模型文本直接赋给 innerHTML，避免不可信模型输出带来 XSS；解析异常则回退成纯文本。
 */
function renderMarkdown(container, markdown) {
    try {
        if (typeof markdown !== 'string') throw new TypeError('Markdown content must be a string');
        const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
        const fragment = document.createDocumentFragment();
        let index = 0;
        while (index < lines.length) {
            const line = lines[index];
            if (!line.trim()) { index++; continue; }
            if (line.startsWith('```')) {
                const language = line.slice(3).trim(); const code = []; index++;
                while (index < lines.length && !lines[index].startsWith('```')) code.push(lines[index++]);
                if (index < lines.length) index++;
                const pre = document.createElement('pre'); const codeNode = document.createElement('code');
                if (language) codeNode.dataset.language = language;
                codeNode.textContent = code.join('\n'); pre.appendChild(codeNode); fragment.appendChild(pre); continue;
            }
            if (isMarkdownTableStart(lines, index)) {
                const wrapper = document.createElement('div'); wrapper.className = 'markdown-table-wrap';
                const table = document.createElement('table');
                const thead = document.createElement('thead'); const headerRow = document.createElement('tr');
                const headers = parseMarkdownTableRow(lines[index]);
                headers.forEach(text => { const cell = document.createElement('th'); appendInlineMarkdown(cell, text); headerRow.appendChild(cell); });
                thead.appendChild(headerRow); table.appendChild(thead); index += 2;
                const tbody = document.createElement('tbody');
                let previousRow = null;
                while (index < lines.length && isMarkdownTableRow(lines[index])) {
                    const values = normalizeMarkdownTableRow(parseMarkdownTableRow(lines[index]), headers.length);
                    if (isResidenceRoomContinuation(headers, values, previousRow)) {
                        mergeResidenceRoomContinuation(previousRow, values);
                        index++;
                        continue;
                    }
                    const row = document.createElement('tr');
                    values.forEach(text => { const cell = document.createElement('td'); appendInlineMarkdown(cell, text); row.appendChild(cell); });
                    tbody.appendChild(row); previousRow = row; index++;
                }
                table.appendChild(tbody); wrapper.appendChild(table); fragment.appendChild(wrapper); continue;
            }
            const heading = line.match(/^(#{1,6})\s+(.+)$/);
            if (heading) { const node = document.createElement(`h${heading[1].length}`); appendInlineMarkdown(node, heading[2]); fragment.appendChild(node); index++; continue; }
            if (/^\s*[-*+]\s+/.test(line)) {
                const list = document.createElement('ul');
                while (index < lines.length && /^\s*[-*+]\s+/.test(lines[index])) { const item = document.createElement('li'); appendInlineMarkdown(item, lines[index].replace(/^\s*[-*+]\s+/, '')); list.appendChild(item); index++; }
                fragment.appendChild(list); continue;
            }
            if (/^\s*\d+[.)]\s+/.test(line)) {
                const list = document.createElement('ol');
                while (index < lines.length && /^\s*\d+[.)]\s+/.test(lines[index])) { const item = document.createElement('li'); appendInlineMarkdown(item, lines[index].replace(/^\s*\d+[.)]\s+/, '')); list.appendChild(item); index++; }
                fragment.appendChild(list); continue;
            }
            if (line.startsWith('>')) { const quote = document.createElement('blockquote'); appendInlineMarkdown(quote, line.replace(/^>\s?/, '')); fragment.appendChild(quote); index++; continue; }
            if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { fragment.appendChild(document.createElement('hr')); index++; continue; }
            const paragraph = [];
            while (index < lines.length && lines[index].trim() && !lines[index].startsWith('```') && !isMarkdownTableStart(lines, index) && !/^(#{1,6})\s+/.test(lines[index]) && !/^\s*[-*+]\s+/.test(lines[index]) && !/^\s*\d+[.)]\s+/.test(lines[index]) && !lines[index].startsWith('>')) paragraph.push(lines[index++]);
            const node = document.createElement('p');
            paragraph.forEach((text, position) => { if (position) node.appendChild(document.createElement('br')); appendInlineMarkdown(node, text); });
            fragment.appendChild(node);
        }
        container.replaceChildren(fragment);
    } catch (error) {
        console.warn('Markdown rendering failed; falling back to plain text.', error);
        container.textContent = String(markdown ?? '');
    }
}

function isMarkdownTableRow(line) {
    return typeof line === 'string' && /^\s*\|.*\|\s*$/.test(line);
}

function isMarkdownTableStart(lines, index) {
    if (!isMarkdownTableRow(lines[index]) || index + 1 >= lines.length) return false;
    const separator = parseMarkdownTableRow(lines[index + 1]);
    return separator.length > 0 && separator.every(cell => /^:?-{3,}:?$/.test(cell));
}

function parseMarkdownTableRow(line) {
    return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
}

function normalizeMarkdownTableRow(values, columnCount) {
    const normalized = values.slice(0, columnCount);
    while (normalized.length < columnCount) normalized.push('');
    return normalized;
}

function isResidenceRoomContinuation(headers, values, previousRow) {
    return Boolean(previousRow)
        && headers[0] === '公寓'
        && headers[1] === '位置参考'
        && !values[0]
        && !values[1]
        && values.slice(2).some(Boolean);
}

function mergeResidenceRoomContinuation(row, values) {
    const cells = Array.from(row.cells);
    for (let index = 2; index < Math.min(cells.length, values.length); index++) {
        let value = values[index];
        if (!value || value === '同上') continue;
        value = value.replace(/^同上[，,、:：\s]*/, '');
        if (!value) continue;
        cells[index].appendChild(document.createElement('br'));
        appendInlineMarkdown(cells[index], value);
    }
}

function appendInlineMarkdown(container, text) {
    const pattern = /(<br\s*\/?>|\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\[[^\]]+\]\([^\s)]+\)|\*[^*]+\*|_[^_]+_)/gi;
    let cursor = 0;
    for (const match of text.matchAll(pattern)) {
        container.append(document.createTextNode(text.slice(cursor, match.index)));
        const token = match[0];
        if (/^<br\s*\/?>$/i.test(token)) container.appendChild(document.createElement('br'));
        else if ((token.startsWith('**') && token.endsWith('**')) || (token.startsWith('__') && token.endsWith('__'))) { const strong = document.createElement('strong'); strong.textContent = token.slice(2, -2); container.appendChild(strong); }
        else if (token.startsWith('`')) { const code = document.createElement('code'); code.textContent = token.slice(1, -1); container.appendChild(code); }
        else if (token.startsWith('[')) { const end = token.indexOf(']('); const label = token.slice(1, end); const href = token.slice(end + 2, -1); const link = document.createElement('a'); link.textContent = label; if (/^(https?:|mailto:)/i.test(href)) { link.href = href; link.target = '_blank'; link.rel = 'noopener noreferrer'; } container.appendChild(link); }
        else { const emphasis = document.createElement('em'); emphasis.textContent = token.slice(1, -1); container.appendChild(emphasis); }
        cursor = match.index + token.length;
    }
    container.append(document.createTextNode(text.slice(cursor)));
}

function appendMessage(role, content, references = []) {
    const list = $('#message-list'); const item = document.createElement('article'); item.className = `message ${role === 'USER' ? 'user-message' : 'assistant-message'}`;
    const label = role === 'USER' ? '你' : 'Londonist AI';
    item.innerHTML = `<span class="message-role">${label}</span><div class="message-content"></div>`;
    const messageContent = item.querySelector('.message-content');
    if (role === 'ASSISTANT') renderMarkdown(messageContent, content || '');
    else messageContent.textContent = content || '';
    renderReferences(item, references); list.appendChild(item); list.scrollTop = list.scrollHeight;
    return item;
}

function renderReferences(messageItem, references) {
    messageItem.querySelector('.references')?.remove();
    if (!references?.length) return;
    const refs = document.createElement('details'); refs.className = 'references';
    refs.innerHTML = `<summary>引用来源（${references.length}）</summary>`;
    references.forEach((ref, index) => {
        const row = document.createElement('div'); const title = document.createElement(ref.docId ? 'button' : 'strong');
        title.textContent = `[${index + 1}] ${ref.title || '无标题'}`;
        if (ref.docId) { title.className = 'reference-link'; title.type = 'button'; title.dataset.reference = JSON.stringify(ref); }
        const content = document.createElement('p'); content.textContent = ref.content || ref.snippet || '';
        row.append(title, content); refs.appendChild(row);
    });
    messageItem.appendChild(refs);
}

async function openReferenceDetail(reference) {
    $('#reference-title').textContent = reference.title || '引用详情';
    $('#reference-snippet').textContent = reference.content || reference.snippet || ''; $('#reference-file-name').textContent = '正在加载文档信息…'; $('#reference-dialog').showModal();
    try {
        const document = await request(`/api/documents/${reference.docId}`);
        $('#reference-title').textContent = document.title || reference.title || '引用详情'; $('#reference-file-name').textContent = document.fileName || '';
    } catch (error) { $('#reference-file-name').textContent = `无法获取文档详情：${error.message}`; }
}

async function askQuestion(event) {
    event.preventDefault(); const question = $('#chat-question').value.trim(); if (!question) return;
    const selectedCategories = [...document.querySelectorAll('.filter-chip.active[data-category]')].map(b => b.dataset.category).filter(Boolean).map(Number);
    $('#chat-empty').classList.add('hidden'); appendMessage('USER', question); $('#chat-question').value = '';
    const button = $('#chat-send'); button.disabled = true; button.textContent = '思考中…';
    const assistantItem = appendMessage('ASSISTANT', ''); const content = assistantItem.querySelector('.message-content'); content.textContent = '正在思考'; content.classList.add('thinking'); let answer = '';
    try {
        await streamRequest('/api/chat/ask/stream', {question, sessionId: state.activeSessionId, categoryIds: selectedCategories}, (eventName, data) => {
            if (eventName === 'meta') state.activeSessionId = data.sessionId;
            if (eventName === 'intent_start' || eventName === 'intent_done' || eventName === 'route_start'
                || eventName === 'tool_start' || eventName === 'tool_done' || eventName === 'tool_error') {
                content.textContent = data.content || '正在处理…';
                content.classList.add('thinking', 'tool-progress');
                content.dataset.toolName = data.toolName || eventName;
                $('#message-list').scrollTop = $('#message-list').scrollHeight;
            }
            if (eventName === 'chunk') { answer += data.content || ''; content.classList.remove('thinking', 'tool-progress'); delete content.dataset.toolName; content.textContent = answer; $('#message-list').scrollTop = $('#message-list').scrollHeight; }
            if (eventName === 'done') { state.activeSessionId = data.sessionId; content.classList.remove('thinking', 'tool-progress'); delete content.dataset.toolName; renderMarkdown(content, answer); renderReferences(assistantItem, data.references || []); loadSessions(); }
            if (eventName === 'error') {
                state.activeSessionId = data.sessionId || state.activeSessionId;
                const message = data.content || '模型响应中断，请重试。';
                content.classList.remove('thinking', 'tool-progress');
                delete content.dataset.toolName;
                renderMarkdown(content, answer ? `${answer}\n\n> ${message}` : `> ${message}`);
                loadSessions();
            }
        });
    }
    catch (error) { content.classList.remove('thinking', 'tool-progress'); delete content.dataset.toolName; content.textContent = `请求失败：${error.message}`; }
    finally { button.disabled = false; button.textContent = '发送'; }
}

const residenceRegionNames = {east: '东部', west: '西部', north: '北部', south: '南部'};
function safeMapUrl(value) {
    try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) ? url.href : ''; }
    catch (_) { return ''; }
}
async function loadResidences() {
    if (state.view !== 'residences') return;
    try {
        const query = new URLSearchParams({name: $('#residence-name').value.trim(), city: $('#residence-city').value, region: $('#residence-region').value, page: state.residencePage, size: state.residenceSize});
        const [result, stats] = await Promise.all([request(`/api/residences?${query}`), request('/api/residences/stats')]);
        state.residenceTotal = result.total;
        renderResidences(result.records);
        const pages = Math.max(Math.ceil(result.total / state.residenceSize), 1);
        $('#residence-pagination-info').textContent = `共 ${result.total} 个公寓`;
        $('#residence-page-number').textContent = `${state.residencePage} / ${pages}`;
        $('#residence-prev').disabled = state.residencePage <= 1;
        $('#residence-next').disabled = state.residencePage >= pages;
        const cities = stats.cities || {};
        const selectedCity = $('#residence-city').value;
        $('#residence-city').innerHTML = `<option value="">全部城市</option>${Object.keys(cities).sort((a, b) => a.localeCompare(b)).map(city => `<option value="${escapeHtml(city)}">${escapeHtml(city)}（${cities[city]}）</option>`).join('')}`;
        if ([...$('#residence-city').options].some(option => option.value === selectedCity)) $('#residence-city').value = selectedCity;
        $('#residence-total').textContent = stats.total || 0;
        $('#residence-east').textContent = cities.London || 0;
        $('#residence-west').textContent = cities.Manchester || 0;
        $('#residence-other').textContent = Math.max((stats.total || 0) - (cities.London || 0) - (cities.Manchester || 0), 0);
        $('#residence-updated').textContent = stats.lastUpdated ? `最近同步：${formatDate(stats.lastUpdated)}` : '地址来自 Londonist 地图 HTML。';
    } catch (error) { showToast(error.message); }
}
function renderResidences(records) {
    const body = $('#residence-table-body');
    body.innerHTML = records.map(residence => {
        const mapUrl = safeMapUrl(residence.mapUrl);
        const coordinates = residence.latitude != null && residence.longitude != null ? `${residence.latitude}, ${residence.longitude}` : '';
        const adminActions = isAdmin() ? `<button class="text-button" data-residence-action="edit" data-id="${residence.id}">编辑地址</button><button class="text-button danger" data-residence-action="delete" data-id="${residence.id}" data-name="${escapeHtml(residence.name)}">删除</button>` : '';
        const actions = `<div class="row-actions"><button class="text-button" data-residence-action="detail" data-id="${residence.id}">查看详情</button>${adminActions}</div>`;
        return `<tr><td><strong class="residence-name">${escapeHtml(residence.name)}</strong><small>${escapeHtml(residence.sourceId)}</small></td><td>${escapeHtml(residence.city || '-')}</td><td><span class="region-tag ${escapeHtml(residence.region || '')}">${escapeHtml(residenceRegionNames[residence.region] || residence.region || '-')}</span><small>${escapeHtml(residence.zone || '-')}</small></td><td><span class="residence-address">${escapeHtml(residence.address || '地址待补充')}</span>${coordinates ? `<small>${escapeHtml(coordinates)}</small>` : ''}</td><td>${escapeHtml(residence.station || '-')}</td><td>${mapUrl ? `<a class="map-link" href="${escapeHtml(mapUrl)}" target="_blank" rel="noopener noreferrer">查看地图 ↗</a>` : '-'}</td><td>${actions}</td></tr>`;
    }).join('');
    $('#residence-empty').classList.toggle('hidden', records.length !== 0);
}
async function openResidenceDialog(id = null) {
    $('#residence-form').reset();
    $('#residence-form-error').textContent = '';
    $('#residence-id').value = '';
    $('#residence-form-city').value = 'London';
    $('#residence-active').value = '1';
    $('#residence-dialog-title').textContent = id ? '编辑公寓' : '新增公寓';
    if (id) {
        try {
            const residence = await request(`/api/residences/${id}`);
            $('#residence-id').value = residence.id;
            $('#residence-source-id').value = residence.sourceId || '';
            $('#residence-name').value = residence.name || '';
            $('#residence-form-city').value = residence.city || '';
            $('#residence-active').value = String(residence.active ?? 1);
            $('#residence-form-region').value = residence.region || '';
            $('#residence-zone').value = residence.zone || '';
            $('#residence-address').value = residence.address || '';
            $('#residence-station').value = residence.station || '';
            $('#residence-latitude').value = residence.latitude ?? '';
            $('#residence-longitude').value = residence.longitude ?? '';
            $('#residence-map-url').value = residence.mapUrl || '';
        } catch (error) { return showToast(error.message); }
    }
    $('#residence-dialog').showModal();
}
async function saveResidence(event) {
    event.preventDefault();
    const id = $('#residence-id').value;
    const numberOrNull = value => value === '' ? null : Number(value);
    const payload = {
        id: id ? Number(id) : null,
        sourceId: $('#residence-source-id').value.trim(),
        name: $('#residence-name').value.trim(),
        city: $('#residence-form-city').value.trim(),
        active: Number($('#residence-active').value),
        region: $('#residence-form-region').value || null,
        zone: $('#residence-zone').value.trim() || null,
        address: $('#residence-address').value.trim(),
        station: $('#residence-station').value.trim() || null,
        latitude: numberOrNull($('#residence-latitude').value),
        longitude: numberOrNull($('#residence-longitude').value),
        mapUrl: $('#residence-map-url').value.trim() || null
    };
    try {
        await request('/api/residences', {method: 'POST', body: JSON.stringify(payload)});
        $('#residence-dialog').close();
        state.residenceOptions = [];
        showToast('公寓已保存');
        await loadResidences();
    } catch (error) { $('#residence-form-error').textContent = error.message; }
}
async function importResidences(event) {
    event.preventDefault();
    const file = $('#residence-file').files[0];
    if (!file) return;
    const button = $('#residence-import-submit');
    const form = new FormData();
    form.append('file', file);
    $('#residence-import-error').textContent = '';
    button.disabled = true; button.textContent = '正在同步…';
    try {
        const result = await request('/api/residences/import', {method: 'POST', body: form});
        $('#residence-import-dialog').close(); event.target.reset();
        showToast(`同步完成：新增 ${result.inserted}，更新 ${result.updated}，未变化 ${result.unchanged}`);
        state.residencePage = 1; await loadResidences();
    } catch (error) { $('#residence-import-error').textContent = error.message; }
    finally { button.disabled = false; button.textContent = '开始同步'; }
}
function nearbyLines(places, type) {
    return (places || []).filter(item => item.placeType === type)
        .map(item => `${item.placeName}${item.travelDescription ? ` | ${item.travelDescription}` : ''}`)
        .join('\n');
}
function parseNearbyLines(value, placeType) {
    return value.split('\n').map(line => line.trim()).filter(Boolean).map((line, index) => {
        const separator = line.indexOf('|');
        return {
            placeType,
            placeName: (separator >= 0 ? line.slice(0, separator) : line).trim(),
            travelDescription: separator >= 0 ? line.slice(separator + 1).trim() || null : null,
            sortOrder: index
        };
    });
}
async function openResidenceDetailDialog(residenceId) {
    $('#residence-detail-form').reset();
    $('#residence-detail-form-error').textContent = '';
    try {
        const detail = await request(`/api/residence-details/${residenceId}`);
        $('#residence-detail-id').value = detail.residenceId;
        $('#residence-detail-title').textContent = `${detail.residenceName} · 详情`;
        $('#detail-official-id').value = detail.officialId || '';
        $('#detail-postcode').value = detail.postcode || '';
        $('#detail-transport-lines').value = detail.transportLines || '';
        $('#detail-official-url').value = detail.officialUrl || '';
        $('#detail-page-tags').value = detail.pageTags || '';
        $('#detail-facilities').value = (detail.facilities || []).join('\n');
        $('#detail-universities').value = nearbyLines(detail.nearbyPlaces, 'UNIVERSITY');
        $('#detail-landmarks').value = nearbyLines(detail.nearbyPlaces, 'LANDMARK');
        [...$('#residence-detail-form').elements].forEach(element => {
            if (element.matches('input, textarea')) element.readOnly = !isAdmin();
        });
        $('#residence-detail-dialog').showModal();
    } catch (error) { showToast(error.message); }
}
async function saveResidenceDetail(event) {
    event.preventDefault();
    if (!isAdmin()) return;
    const payload = {
        residenceId: Number($('#residence-detail-id').value),
        officialId: $('#detail-official-id').value.trim() || null,
        postcode: $('#detail-postcode').value.trim() || null,
        transportLines: $('#detail-transport-lines').value.trim() || null,
        officialUrl: $('#detail-official-url').value.trim() || null,
        pageTags: $('#detail-page-tags').value.trim() || null,
        facilities: $('#detail-facilities').value.split('\n').map(item => item.trim()).filter(Boolean),
        nearbyPlaces: [
            ...parseNearbyLines($('#detail-universities').value, 'UNIVERSITY'),
            ...parseNearbyLines($('#detail-landmarks').value, 'LANDMARK')
        ]
    };
    try {
        await request('/api/residence-details', {method: 'POST', body: JSON.stringify(payload)});
        $('#residence-detail-dialog').close();
        showToast('公寓详情已保存，下一次 Tool 查询立即生效');
    } catch (error) { $('#residence-detail-form-error').textContent = error.message; }
}
async function importResidenceDetails(event) {
    event.preventDefault();
    const file = $('#residence-detail-file').files[0];
    if (!file) return;
    const form = new FormData(); form.append('file', file);
    const button = $('#residence-detail-import-submit');
    $('#residence-detail-import-error').textContent = '';
    $('#residence-detail-import-result').classList.add('hidden');
    button.disabled = true; button.textContent = '正在解析并导入…';
    try {
        const result = await request('/api/residence-details/import', {method: 'POST', body: form});
        const warnings = (result.warnings || []).map(item => `<li>${escapeHtml(item)}</li>`).join('');
        $('#residence-detail-import-result').innerHTML = `<strong>导入完成</strong><p>识别 ${result.total} 个公寓，成功 ${result.imported}，未匹配 ${result.unmatched}。</p>${warnings ? `<details><summary>查看未匹配项</summary><ul>${warnings}</ul></details>` : ''}`;
        $('#residence-detail-import-result').classList.remove('hidden');
        showToast('公寓详情已导入，Tool 查询立即生效');
    } catch (error) { $('#residence-detail-import-error').textContent = error.message; }
    finally { button.disabled = false; button.textContent = '开始导入'; }
}

const offerStatusLabels = {AVAILABLE: '可预订', LIMITED: '库存紧张', SOLD_OUT: '已售罄', UNKNOWN: '待确认'};
const offerStatusClasses = {AVAILABLE: 'success', LIMITED: 'processing', SOLD_OUT: 'fail', UNKNOWN: 'unknown'};
function localNowInput() {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
async function loadResidenceOptions(force = false) {
    if (!state.residenceOptions.length || force) state.residenceOptions = await request('/api/residences/options');
    const filterValue = $('#offer-residence').value;
    const formValue = $('#offer-form-residence').value;
    const recommendationValue = $('#recommendation-residence').value;
    const options = state.residenceOptions.map(item => `<option value="${item.id}">${escapeHtml(item.name)} · ${escapeHtml(item.sourceId)}</option>`).join('');
    $('#offer-residence').innerHTML = `<option value="">全部公寓</option>${options}`;
    $('#offer-form-residence').innerHTML = `<option value="">请选择公寓</option>${options}`;
    $('#recommendation-residence').innerHTML = `<option value="">请选择公寓</option>${options}`;
    if ([...$('#offer-residence').options].some(option => option.value === filterValue)) $('#offer-residence').value = filterValue;
    if ([...$('#offer-form-residence').options].some(option => option.value === formValue)) $('#offer-form-residence').value = formValue;
    if ([...$('#recommendation-residence').options].some(option => option.value === recommendationValue)) $('#recommendation-residence').value = recommendationValue;
}
async function loadOffers() {
    if (state.view !== 'offers') return;
    try {
        await loadResidenceOptions();
        const query = new URLSearchParams({keyword: $('#offer-keyword').value.trim(), status: $('#offer-status').value, page: state.offerPage, size: state.offerSize});
        if ($('#offer-residence').value) query.set('residenceId', $('#offer-residence').value);
        const [result, stats, imports] = await Promise.all([
            request(`/api/room-offers/page?${query}`),
            request('/api/room-offers/stats'),
            request('/api/room-offers/imports?limit=5')
        ]);
        state.offerTotal = result.total;
        renderOffers(result.records);
        renderOfferImports(imports);
        const pages = Math.max(Math.ceil(result.total / state.offerSize), 1);
        $('#offer-pagination-info').textContent = `共 ${result.total} 个房型`;
        $('#offer-page-number').textContent = `${state.offerPage} / ${pages}`;
        $('#offer-prev').disabled = state.offerPage <= 1;
        $('#offer-next').disabled = state.offerPage >= pages;
        $('#offer-total').textContent = stats.total || 0;
        $('#offer-available').textContent = stats.available || 0;
        $('#offer-limited').textContent = stats.limited || 0;
        $('#offer-sold-out').textContent = stats.soldOut || 0;
    } catch (error) { showToast(error.message); }
}
function renderOffers(records) {
    $('#offer-table-body').innerHTML = records.map(offer => {
        const tiers = [...(offer.priceTiers || [])].sort((a, b) => a.minWeeks - b.minWeeks);
        const tierHtml = tiers.map(tier => `<span class="price-chip"><b>${tier.minWeeks}${tier.maxWeeks == null ? '+' : `–${tier.maxWeeks}`}周</b>${escapeHtml(tier.currency)} ${Number(tier.weeklyPrice).toFixed(2)}</span>`).join('');
        const status = offer.inventoryStatus || 'UNKNOWN';
        const quantity = offer.remainingQuantity == null ? '数量未知' : `剩余 ${offer.remainingQuantity}`;
        const actions = isAdmin() ? `<div class="row-actions"><button class="text-button" data-offer-action="edit" data-id="${offer.id}" type="button">编辑</button><button class="text-button danger" data-offer-action="delete" data-id="${offer.id}" data-name="${escapeHtml(offer.roomName)}" type="button">删除</button></div>` : '<span class="muted">只读</span>';
        return `<tr><td><strong class="offer-room-name">${escapeHtml(offer.roomName)}</strong><small>${escapeHtml(offer.residenceName)} · ${escapeHtml(offer.roomCode)}</small><span class="root-type-tag">${escapeHtml(offer.rootType)}</span></td><td><span class="date-range">${formatDateOnly(offer.earliestStartDate)} → ${formatDateOnly(offer.latestEndDate)}</span></td><td><span class="status-tag ${offerStatusClasses[status] || 'unknown'}">${escapeHtml(offerStatusLabels[status] || status)}</span><small>${escapeHtml(quantity)}</small></td><td><div class="price-chip-list">${tierHtml || '<span class="muted">暂无价格</span>'}</div></td><td>${formatDate(offer.inventoryUpdatedAt)}${offer.sourceFileName ? `<small>来源：${escapeHtml(offer.sourceFileName)}</small>` : ''}</td><td>${actions}</td></tr>`;
    }).join('');
    $('#offer-empty').classList.toggle('hidden', records.length !== 0);
}
function renderOfferImports(records) {
    $('#offer-import-history').innerHTML = records.length ? records.map(item => `<article><div><strong>${escapeHtml(item.fileName)}</strong><p>${escapeHtml(item.message || '导入完成')}</p></div><div><span class="status-tag success">${escapeHtml(item.status)}</span><small>${formatDate(item.finishTime || item.createTime)}</small></div></article>`).join('') : '<p class="empty-state">还没有批量导入记录。</p>';
}
function priceTierEditor(tier = {}) {
    return `<article class="price-tier-card"><div class="price-tier-grid"><label>最短周数<input class="tier-min" type="number" min="1" max="104" required value="${tier.minWeeks ?? ''}"></label><label>最长周数 <span class="optional">（以上留空）</span><input class="tier-max" type="number" min="1" max="104" value="${tier.maxWeeks ?? ''}"></label><label>每周价格<input class="tier-price" type="number" min="0.01" step="0.01" required value="${tier.weeklyPrice ?? ''}"></label><label>币种<select class="tier-currency"><option value="GBP">GBP</option><option value="EUR">EUR</option><option value="CNY">CNY</option></select></label><label class="tier-updated-field">价格更新时间<input class="tier-updated" type="datetime-local" required value="${toLocalDateTimeInput(tier.priceUpdatedAt) || localNowInput()}"></label><label class="tier-note-field">备注<input class="tier-note" maxlength="1024" value="${escapeHtml(tier.note || '')}"></label></div><button class="icon-button remove-price-tier" type="button" title="删除档位">×</button></article>`;
}
function addPriceTier(tier = {}) {
    $('#price-tier-list').insertAdjacentHTML('beforeend', priceTierEditor(tier));
    const card = $('#price-tier-list').lastElementChild;
    card.querySelector('.tier-currency').value = tier.currency || 'GBP';
}
async function openOfferDialog(id = null) {
    $('#offer-form').reset();
    $('#offer-form-error').textContent = '';
    $('#price-tier-list').replaceChildren();
    $('#offer-id').value = id || '';
    $('#offer-dialog-title').textContent = id ? '编辑房型' : '新增房型';
    $('#offer-inventory-updated-at').value = localNowInput();
    $('#offer-inventory-status').value = 'AVAILABLE';
    try {
        await loadResidenceOptions();
        if (id) {
            const offer = await request(`/api/room-offers/${id}`);
            $('#offer-form-residence').value = offer.residenceId;
            $('#offer-room-code').value = offer.roomCode || '';
            $('#offer-room-name').value = offer.roomName || '';
            $('#offer-root-type').value = offer.rootType || 'Other';
            $('#offer-start-date').value = offer.earliestStartDate || '';
            $('#offer-end-date').value = offer.latestEndDate || '';
            $('#offer-quantity').value = offer.remainingQuantity ?? '';
            $('#offer-inventory-status').value = offer.inventoryStatus || 'UNKNOWN';
            $('#offer-inventory-updated-at').value = toLocalDateTimeInput(offer.inventoryUpdatedAt);
            $('#offer-note').value = offer.note || '';
            (offer.priceTiers || []).sort((a, b) => a.minWeeks - b.minWeeks).forEach(addPriceTier);
        } else {
            addPriceTier();
        }
        $('#offer-dialog').showModal();
    } catch (error) { showToast(error.message); }
}
async function saveOffer(event) {
    event.preventDefault();
    $('#offer-form-error').textContent = '';
    const quantityValue = $('#offer-quantity').value;
    const priceTiers = [...document.querySelectorAll('.price-tier-card')].map(card => ({
        minWeeks: Number(card.querySelector('.tier-min').value),
        maxWeeks: card.querySelector('.tier-max').value ? Number(card.querySelector('.tier-max').value) : null,
        weeklyPrice: Number(card.querySelector('.tier-price').value),
        currency: card.querySelector('.tier-currency').value,
        priceUpdatedAt: card.querySelector('.tier-updated').value,
        note: card.querySelector('.tier-note').value.trim()
    }));
    const payload = {
        id: $('#offer-id').value ? Number($('#offer-id').value) : null,
        residenceId: Number($('#offer-form-residence').value),
        roomCode: $('#offer-room-code').value.trim(),
        roomName: $('#offer-room-name').value.trim(),
        rootType: $('#offer-root-type').value,
        earliestStartDate: $('#offer-start-date').value,
        latestEndDate: $('#offer-end-date').value,
        remainingQuantity: quantityValue === '' ? null : Number(quantityValue),
        inventoryStatus: $('#offer-inventory-status').value,
        inventoryUpdatedAt: $('#offer-inventory-updated-at').value,
        note: $('#offer-note').value.trim(),
        priceTiers
    };
    const button = $('#offer-save-button');
    button.disabled = true; button.textContent = '保存中…';
    try {
        await request('/api/room-offers', {method: 'POST', body: JSON.stringify(payload)});
        $('#offer-dialog').close(); showToast('房型库存与价格已保存'); await loadOffers();
    } catch (error) { $('#offer-form-error').textContent = error.message; }
    finally { button.disabled = false; button.textContent = '保存房型'; }
}
async function importOffers(event) {
    event.preventDefault();
    const file = $('#offer-import-file').files[0];
    if (!file) return;
    const form = new FormData(); form.append('file', file);
    const button = $('#offer-import-submit');
    $('#offer-import-error').textContent = '';
    $('#offer-import-result').classList.add('hidden');
    button.disabled = true; button.textContent = '正在校验并导入…';
    try {
        const result = await request('/api/room-offers/import', {method: 'POST', body: form});
        const warnings = (result.warnings || []).map(item => `<li>${escapeHtml(item)}</li>`).join('');
        $('#offer-import-result').innerHTML = `<strong>导入完成</strong><p>库存：新增 ${result.inventoryInserted}，更新 ${result.inventoryUpdated}；价格：新增 ${result.priceInserted}，更新 ${result.priceUpdated}；跳过 ${result.skipped}。</p>${warnings ? `<details><summary>${result.warnings.length} 条名称匹配提示</summary><ul>${warnings}</ul></details>` : ''}`;
        $('#offer-import-result').classList.remove('hidden');
        showToast('结构化模板导入成功');
        state.offerPage = 1; await loadOffers();
    } catch (error) { $('#offer-import-error').textContent = error.message; }
    finally { button.disabled = false; button.textContent = '开始导入'; }
}

async function loadRecommendations() {
    if (state.view !== 'recommendations' || !isAdmin()) return;
    try {
        const records = await request('/api/sales-recommendations');
        renderRecommendations(records);
    } catch (error) { showToast(error.message); }
}
function renderRecommendations(records) {
    $('#recommendation-table-body').innerHTML = records.map(item => {
        const enabled = item.enabled === 1;
        return `<tr><td><strong class="recommendation-priority">${item.priority}</strong></td><td><strong>${escapeHtml(item.residenceName)}</strong><small>${escapeHtml(item.residenceSourceId)}</small></td><td>${escapeHtml(item.city || '-')}</td><td><span class="status-tag ${enabled ? 'active' : 'disabled'}">${enabled ? '启用' : '停用'}</span></td><td>${escapeHtml(item.note || '-')}</td><td>${formatDate(item.updateTime)}</td><td><div class="row-actions"><button class="text-button" data-recommendation-action="edit" data-id="${item.id}" type="button">编辑</button><button class="text-button danger" data-recommendation-action="delete" data-id="${item.id}" data-name="${escapeHtml(item.residenceName)}" type="button">删除</button></div></td></tr>`;
    }).join('');
    $('#recommendation-empty').classList.toggle('hidden', records.length !== 0);
}
async function openRecommendationDialog(id = null) {
    $('#recommendation-form').reset();
    $('#recommendation-form-error').textContent = '';
    $('#recommendation-id').value = '';
    $('#recommendation-priority').value = '100';
    $('#recommendation-enabled').value = '1';
    $('#recommendation-dialog-title').textContent = id ? '编辑推荐公寓' : '添加推荐公寓';
    try {
        await loadResidenceOptions();
        if (id) {
            const item = await request(`/api/sales-recommendations/${id}`);
            $('#recommendation-id').value = item.id;
            $('#recommendation-residence').value = String(item.residenceId);
            $('#recommendation-priority').value = item.priority;
            $('#recommendation-enabled').value = String(item.enabled);
            $('#recommendation-note').value = item.note || '';
        }
        $('#recommendation-dialog').showModal();
    } catch (error) { showToast(error.message); }
}
async function saveRecommendation(event) {
    event.preventDefault();
    const id = $('#recommendation-id').value;
    const payload = {
        id: id ? Number(id) : null,
        residenceId: Number($('#recommendation-residence').value),
        priority: Number($('#recommendation-priority').value),
        enabled: Number($('#recommendation-enabled').value),
        note: $('#recommendation-note').value.trim() || null
    };
    try {
        await request('/api/sales-recommendations', {method: 'POST', body: JSON.stringify(payload)});
        $('#recommendation-dialog').close();
        showToast('推荐配置已保存，下一次房源推荐立即生效');
        await loadRecommendations();
    } catch (error) { $('#recommendation-form-error').textContent = error.message; }
}

async function loadUsers() {
    if (!isAdmin()) return;
    try { const query = new URLSearchParams({keyword: $('#keyword').value.trim(), page: state.userPage, size: state.userSize}); const result = await request(`/api/users/page?${query}`); state.userTotal = result.total; renderUsers(result.records); const pages = Math.max(Math.ceil(result.total / state.userSize), 1); $('#pagination-info').textContent = `共 ${result.total} 位用户`; $('#page-number').textContent = `${state.userPage} / ${pages}`; $('#prev-page').disabled = state.userPage <= 1; $('#next-page').disabled = state.userPage >= pages; } catch (error) { showToast(error.message); }
}
function renderUsers(records) {
    const body = $('#user-table-body'); body.innerHTML = records.map(user => { const tr = document.createElement('tr'); tr.dataset.user = JSON.stringify(user); tr.innerHTML = `<td><div class="user-cell">${avatarMarkup(user)}<span class="username">${escapeHtml(user.username)}</span></div></td><td>${escapeHtml(user.realName || '-')}</td><td><span class="role-tag ${user.role === 'ADMIN' ? 'admin' : 'user'}">${user.role}</span></td><td><span class="status-tag ${user.status === 1 ? 'active' : 'disabled'}">${user.status === 1 ? '正常' : '禁用'}</span></td><td>${formatDate(user.createTime)}</td><td><div class="row-actions"><button class="text-button" data-user-action="edit">编辑</button><button class="text-button danger" data-user-action="delete">删除</button></div></td>`; return tr.outerHTML; }).join(''); $('#empty-state').classList.toggle('hidden', records.length !== 0);
}
function openUserDialog(user = null) { $('#user-form').reset(); $('#dialog-error').textContent = ''; $('#user-id').value = user?.id || ''; $('#dialog-title').textContent = user ? '编辑用户' : '新增用户'; $('#user-username').value = user?.username || ''; $('#user-real-name').value = user?.realName || ''; $('#user-role').value = user?.role || 'USER'; $('#user-status').value = String(user?.status ?? 1); $('#user-password').required = !user; $('#user-dialog').showModal(); }
async function saveUser(event) { event.preventDefault(); const id = $('#user-id').value; const payload = {id: id ? Number(id) : null, username: $('#user-username').value.trim(), realName: $('#user-real-name').value.trim(), role: $('#user-role').value, status: Number($('#user-status').value), password: $('#user-password').value || null}; try { await request('/api/users', {method: 'POST', body: JSON.stringify(payload)}); $('#user-dialog').close(); showToast('用户已保存'); loadUsers(); } catch (error) { $('#dialog-error').textContent = error.message; } }

async function uploadAvatar(event) { const [file] = event.target.files; if (!file) return; const form = new FormData(); form.append('file', file); try { state.user.avatar = await request('/api/users/me/avatar', {method: 'POST', body: form}); localStorage.setItem('veri-rag-user', JSON.stringify(state.user)); renderCurrentUser(); showToast('头像已更新'); } catch (error) { showToast(error.message); } finally { event.target.value = ''; } }
async function saveProfile(event) { event.preventDefault(); try { await request('/api/users/me/profile', {method: 'PUT', body: JSON.stringify({realName: $('#profile-real-name').value.trim()})}); await refreshCurrentUser(); showToast('资料已保存'); } catch (error) { showToast(error.message); } }
async function changePassword(event) { event.preventDefault(); const query = new URLSearchParams({oldPassword: $('#old-password').value, newPassword: $('#new-password').value}); try { await request(`/api/users/me/password?${query}`, {method: 'PUT'}); event.target.reset(); showToast('密码已更新'); } catch (error) { showToast(error.message); } }

$('#login-form').addEventListener('submit', login); $('#logout-button').addEventListener('click', () => logout()); $('#avatar-upload-button').addEventListener('click', () => $('#avatar-input').click()); $('#avatar-input').addEventListener('change', uploadAvatar);
document.querySelectorAll('.nav-item').forEach(item => item.addEventListener('click', () => showView(item.dataset.view)));
$('#new-chat-button').addEventListener('click', newChat); $('#chat-form').addEventListener('submit', askQuestion);
$('#message-list').addEventListener('click', event => { const link = event.target.closest('.reference-link'); if (link) openReferenceDetail(JSON.parse(link.dataset.reference)); });
$('#session-list').addEventListener('click', async event => { const deleteButton = event.target.closest('[data-delete-session]'); if (deleteButton) { event.stopPropagation(); const id = deleteButton.dataset.deleteSession; if (!confirm('确定删除这个会话吗？')) return; try { await request(`/api/chat/sessions/${id}`, {method: 'DELETE'}); if (state.activeSessionId === Number(id)) newChat(); showToast('会话已删除'); loadSessions(); } catch (error) { showToast(error.message); } return; } const item = event.target.closest('.session-item'); if (item) openSession(item.dataset.id); });
$('#chat-category-filter').addEventListener('click', event => { const chip = event.target.closest('.filter-chip'); if (!chip) return; document.querySelectorAll('.filter-chip').forEach(button => button.classList.remove('active')); chip.classList.add('active'); });
$('#new-category-button').addEventListener('click', () => $('#category-dialog').showModal()); $('#category-form').addEventListener('submit', saveCategory); $('#category-list').addEventListener('click', async event => { const button = event.target.closest('.delete-category'); if (!button || !confirm('确定删除该分类吗？')) return; try { await request(`/api/categories/${button.dataset.id}`, {method: 'DELETE'}); showToast('分类已删除'); loadCategories(); } catch (error) { showToast(error.message); } });
$('#upload-document-button').addEventListener('click', () => $('#document-dialog').showModal()); $('#document-form').addEventListener('submit', uploadDocument); $('#document-search-button').addEventListener('click', () => { state.documentPage = 1; loadDocuments(); }); $('#document-category').addEventListener('change', () => { state.documentPage = 1; loadDocuments(); }); $('#document-prev').addEventListener('click', () => { if (state.documentPage > 1) { state.documentPage--; loadDocuments(); } }); $('#document-next').addEventListener('click', () => { if (state.documentPage * state.documentSize < state.documentTotal) { state.documentPage++; loadDocuments(); } }); $('#batch-reingest-button').addEventListener('click', batchReingestDocuments); $('#document-list').addEventListener('change', event => { const checkbox = event.target.closest('.document-select'); if (!checkbox) return; const id = Number(checkbox.dataset.id); if (checkbox.checked) state.selectedDocumentIds.add(id); else state.selectedDocumentIds.delete(id); updateBatchReingestButton(); }); $('#document-list').addEventListener('click', async event => { const reingest = event.target.closest('.reingest-document'); if (reingest) { if (!confirm(`确定重新向量化文档“${reingest.dataset.title}”吗？`)) return; reingest.disabled = true; reingest.textContent = '处理中…'; try { await request(`/api/documents/${reingest.dataset.id}/reingest`, {method: 'POST'}); showToast('文档已重新向量化'); loadDocuments(); } catch (error) { showToast(error.message); reingest.disabled = false; reingest.textContent = '重新向量化'; } return; } const button = event.target.closest('.delete-document'); if (!button || !confirm(`确定删除文档“${button.dataset.title}”吗？`)) return; try { await request(`/api/documents/${button.dataset.id}`, {method: 'DELETE'}); state.selectedDocumentIds.delete(Number(button.dataset.id)); showToast('文档和向量已删除'); loadDocuments(); } catch (error) { showToast(error.message); } });
$('#new-residence-button').addEventListener('click', () => openResidenceDialog()); $('#import-residence-button').addEventListener('click', () => $('#residence-import-dialog').showModal()); $('#import-residence-detail-button').addEventListener('click', () => { $('#residence-detail-import-form').reset(); $('#residence-detail-import-error').textContent = ''; $('#residence-detail-import-result').classList.add('hidden'); $('#residence-detail-import-dialog').showModal(); }); $('#residence-form').addEventListener('submit', saveResidence); $('#residence-import-form').addEventListener('submit', importResidences); $('#residence-detail-import-form').addEventListener('submit', importResidenceDetails); $('#residence-detail-form').addEventListener('submit', saveResidenceDetail); $('#residence-search-button').addEventListener('click', () => { state.residencePage = 1; loadResidences(); }); $('#residence-city').addEventListener('change', () => { state.residencePage = 1; loadResidences(); }); $('#residence-region').addEventListener('change', () => { state.residencePage = 1; loadResidences(); }); $('#residence-name').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); state.residencePage = 1; loadResidences(); } }); $('#residence-prev').addEventListener('click', () => { if (state.residencePage > 1) { state.residencePage--; loadResidences(); } }); $('#residence-next').addEventListener('click', () => { if (state.residencePage * state.residenceSize < state.residenceTotal) { state.residencePage++; loadResidences(); } }); $('#residence-table-body').addEventListener('click', async event => { const button = event.target.closest('[data-residence-action]'); if (!button) return; if (button.dataset.residenceAction === 'detail') return openResidenceDetailDialog(Number(button.dataset.id)); if (button.dataset.residenceAction === 'edit') return openResidenceDialog(Number(button.dataset.id)); if (!confirm(`确定删除公寓“${button.dataset.name}”吗？`)) return; try { await request(`/api/residences/${button.dataset.id}`, {method: 'DELETE'}); state.residenceOptions = []; showToast('公寓已删除'); await loadResidences(); } catch (error) { showToast(error.message); } });
$('#new-offer-button').addEventListener('click', () => openOfferDialog()); $('#import-offer-button').addEventListener('click', () => { $('#offer-import-form').reset(); $('#offer-import-error').textContent = ''; $('#offer-import-result').classList.add('hidden'); $('#offer-import-dialog').showModal(); }); $('#offer-form').addEventListener('submit', saveOffer); $('#offer-import-form').addEventListener('submit', importOffers); $('#add-price-tier').addEventListener('click', () => addPriceTier()); $('#price-tier-list').addEventListener('click', event => { const button = event.target.closest('.remove-price-tier'); if (button) button.closest('.price-tier-card').remove(); }); $('#offer-search-button').addEventListener('click', () => { state.offerPage = 1; loadOffers(); }); $('#offer-residence').addEventListener('change', () => { state.offerPage = 1; loadOffers(); }); $('#offer-status').addEventListener('change', () => { state.offerPage = 1; loadOffers(); }); $('#offer-keyword').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); state.offerPage = 1; loadOffers(); } }); $('#offer-prev').addEventListener('click', () => { if (state.offerPage > 1) { state.offerPage--; loadOffers(); } }); $('#offer-next').addEventListener('click', () => { if (state.offerPage * state.offerSize < state.offerTotal) { state.offerPage++; loadOffers(); } }); $('#offer-inventory-status').addEventListener('change', event => { if (event.target.value === 'SOLD_OUT') $('#offer-quantity').value = 0; }); $('#offer-table-body').addEventListener('click', async event => { const button = event.target.closest('[data-offer-action]'); if (!button) return; if (button.dataset.offerAction === 'edit') return openOfferDialog(Number(button.dataset.id)); if (!confirm(`确定删除房型“${button.dataset.name}”及其全部价格档位吗？`)) return; try { await request(`/api/room-offers/${button.dataset.id}`, {method: 'DELETE'}); showToast('房型已删除'); await loadOffers(); } catch (error) { showToast(error.message); } });
$('#new-recommendation-button').addEventListener('click', () => openRecommendationDialog()); $('#recommendation-form').addEventListener('submit', saveRecommendation); $('#recommendation-table-body').addEventListener('click', async event => { const button = event.target.closest('[data-recommendation-action]'); if (!button) return; if (button.dataset.recommendationAction === 'edit') return openRecommendationDialog(Number(button.dataset.id)); if (!confirm(`确定删除“${button.dataset.name}”的推荐配置吗？`)) return; try { await request(`/api/sales-recommendations/${button.dataset.id}`, {method: 'DELETE'}); showToast('推荐配置已删除'); await loadRecommendations(); } catch (error) { showToast(error.message); } });
$('#search-button').addEventListener('click', () => { state.userPage = 1; loadUsers(); }); $('#create-button').addEventListener('click', () => openUserDialog()); $('#prev-page').addEventListener('click', () => { if (state.userPage > 1) { state.userPage--; loadUsers(); } }); $('#next-page').addEventListener('click', () => { if (state.userPage * state.userSize < state.userTotal) { state.userPage++; loadUsers(); } }); $('#user-form').addEventListener('submit', saveUser); $('#close-dialog').addEventListener('click', () => $('#user-dialog').close()); $('#cancel-dialog').addEventListener('click', () => $('#user-dialog').close()); $('#user-table-body').addEventListener('click', async event => { const button = event.target.closest('[data-user-action]'); if (!button) return; const user = JSON.parse(button.closest('tr').dataset.user); if (button.dataset.userAction === 'edit') return openUserDialog(user); if (!confirm(`确定删除用户“${user.username}”吗？`)) return; try { await request(`/api/users/${user.id}`, {method: 'DELETE'}); showToast('用户已删除'); loadUsers(); } catch (error) { showToast(error.message); } });
$('#profile-form').addEventListener('submit', saveProfile); $('#password-form').addEventListener('submit', changePassword); document.querySelectorAll('[data-close-dialog]').forEach(button => button.addEventListener('click', () => $(`#${button.dataset.closeDialog}`).close()));
if (state.token) showConsole();
