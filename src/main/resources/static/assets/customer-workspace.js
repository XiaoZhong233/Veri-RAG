(() => {
    const model = {page: 1, records: [], selected: null, listVersion: 0, detailVersion: 0, historyVersion: 0, before: 0};
    const base = '/api/wecom/kf/admin/customers';
    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }
    function identity(customer) {
        const box = element('div', 'customer-person');
        const avatar = element('span', 'customer-avatar', (customer.nickname || '客').slice(0, 1));
        try {
            const url = new URL(customer.avatar);
            if (url.protocol === 'https:' && !url.username && !url.password) {
                const img = document.createElement('img');
                img.src = url.href; img.alt = ''; img.referrerPolicy = 'no-referrer'; img.loading = 'lazy';
                img.addEventListener('error', () => img.remove());
                avatar.append(img);
            }
        } catch (_) { /* No valid avatar: retain initial. */ }
        const info = element('div');
        info.append(element('strong', '', customer.nickname || '微信客户（未获取昵称）'), element('small', 'muted', customer.externalUserId));
        box.append(avatar, info);
        return box;
    }
    function renderList() {
        const list = $('#customer-list'); list.replaceChildren();
        for (const customer of model.records) {
            const button = element('button', 'customer-card' + (model.selected === customer.id ? ' active' : ''));
            button.type = 'button'; button.append(identity(customer), element('small', 'muted', `最近记录：${formatDate(customer.lastSeenAt)}`));
            button.addEventListener('click', () => openCustomer(customer.id)); list.append(button);
        }
        if (!model.records.length) list.append(element('p', 'empty-state', '暂无匹配客户。昵称需先获取微信资料，暂可按客户标识搜索。'));
    }
    async function loadCustomers(reset = true) {
        if (!isAdmin()) return;
        if (reset) model.page = 1;
        model.selected = null; model.detailVersion++; model.historyVersion++;
        $('#customer-content').classList.add('hidden'); $('#customer-placeholder').classList.remove('hidden');
        $('#customer-placeholder').textContent = '选择一位客户，查看咨询详情。';
        const version = ++model.listVersion;
        $('#customer-list').replaceChildren(element('p', 'empty-state', '正在加载客户…'));
        const query = new URLSearchParams({page: String(model.page), keyword: $('#customer-search').value.trim(), openKfId: $('#wecom-account-select').value});
        try {
            const data = await request(`${base}?${query}`);
            if (version !== model.listVersion) return;
            model.records = data.records; renderList();
            $('#customer-page').textContent = `第 ${model.page} 页`;
            $('#customer-prev').disabled = model.page <= 1; $('#customer-next').disabled = !data.hasMore;
            if (model.records.length) {
                try {
                    const profiles = await request(`${base}/profiles/refresh`, {method: 'POST', body: JSON.stringify({ids: model.records.map(c => c.id)})});
                    if (version === model.listVersion) { model.records = profiles; renderList(); }
                } catch (_) { /* Keep customer IDs and cached profiles when refresh fails. */ }
            }
        } catch (error) {
            if (version === model.listVersion) $('#customer-list').replaceChildren(element('p', 'empty-state', error.message));
        }
    }
    function renderProfile(detail) {
        const c = detail.customer;
        $('#customer-identity').replaceChildren(identity(c));
        $('#customer-profile-warning').textContent = detail.profileWarning || '昵称和头像来自微信接口；客户标识不是微信号。';
        const existing = model.records.findIndex(row => row.id === c.id);
        if (existing >= 0) model.records[existing] = c;
        renderList();
    }
    function renderDetail(detail) {
        renderProfile(detail);
        const c = detail.customer;
        $('#customer-summary-text').textContent = c.serviceSummary || detail.memorySummary || '暂无摘要。点击“生成／更新摘要”整理该客户的咨询情况。';
        $('#customer-summary-status').textContent = c.serviceSummary
            ? `生成依据截至：${formatDate(c.summaryUpdatedAt)}。后续新消息需更新摘要。`
            : detail.memorySummary ? '当前为历史记忆摘要，可能未覆盖近期消息，可生成服务摘要。' : '尚未生成服务摘要';
    }
    async function openCustomer(id) {
        const version = ++model.detailVersion;
        model.selected = id; model.historyVersion++;
        $('#customer-content').classList.add('hidden'); $('#customer-placeholder').classList.remove('hidden');
        $('#customer-placeholder').textContent = '正在加载客户资料…'; renderList();
        try {
            const detail = await request(`${base}/${id}`);
            if (version !== model.detailVersion) return;
            renderDetail(detail);
            $('#customer-content').classList.remove('hidden'); $('#customer-placeholder').classList.add('hidden');
            $('#customer-history-source').value = 'current';
            loadHistory(true);
            if (!detail.customer.profileUpdatedAt) refreshProfile(id, version);
        } catch (error) {
            if (version === model.detailVersion) $('#customer-placeholder').textContent = error.message;
        }
    }
    async function refreshProfile(id = model.selected, version = model.detailVersion) {
        if (!id) return;
        const button = $('#customer-profile-refresh'); button.disabled = true;
        try {
            const detail = await request(`${base}/${id}/profile/refresh`, {method: 'POST'});
            if (version === model.detailVersion) renderProfile(detail);
        } catch (error) { if (version === model.detailVersion) $('#customer-profile-warning').textContent = error.message; }
        finally { button.disabled = false; }
    }
    async function loadHistory(reset) {
        const id = model.selected; if (!id) return;
        const version = ++model.historyVersion;
        const container = $('#customer-messages'); const button = $('#customer-history-more');
        if (reset) { model.before = 0; container.replaceChildren(element('p', 'empty-state', '正在加载聊天记录…')); }
        button.disabled = true;
        const query = new URLSearchParams({before: String(model.before), legacy: String($('#customer-history-source').value === 'legacy')});
        try {
            const page = await request(`${base}/${id}/messages?${query}`);
            if (version !== model.historyVersion || id !== model.selected) return;
            if (reset) container.replaceChildren();
            const fragment = document.createDocumentFragment();
            for (const message of page.records) {
                const role = {CUSTOMER: '客户', HUMAN: '人工客服', BOT: '智能客服', SYSTEM: '系统'}[message.speaker] || '其他';
                const article = element('article', 'customer-message ' + (message.speaker === 'CUSTOMER' ? 'from-customer' : ''));
                article.append(element('small', 'muted', `${role} · ${formatDate(message.sentAt)}`), element('div', 'customer-text', message.content));
                fragment.append(article);
            }
            container.prepend(fragment);
            if (reset && !page.records.length) container.append(element('p', 'empty-state', '暂无此类记录，可切换“历史机器人记录”查看旧咨询。'));
            model.before = page.nextBefore; button.classList.toggle('hidden', !page.hasMore);
        } catch (error) {
            if (version === model.historyVersion) {
                if (reset) container.replaceChildren(element('p', 'empty-state', error.message));
                showToast(error.message);
            }
        }
        finally { if (version === model.historyVersion) button.disabled = false; }
    }
    $('#customer-summary-refresh').addEventListener('click', async () => {
        const id = model.selected, version = model.detailVersion; if (!id) return;
        const button = $('#customer-summary-refresh'); button.disabled = true; button.textContent = '正在生成…';
        try {
            const detail = await request(`${base}/${id}/summary`, {method: 'POST'});
            if (version === model.detailVersion) renderDetail(detail);
        } catch (error) { if (version === model.detailVersion) showToast(error.message); }
        finally { button.disabled = false; button.textContent = '生成／更新摘要'; }
    });
    $('#customer-search-form').addEventListener('submit', event => { event.preventDefault(); loadCustomers(); });
    $('#customer-refresh').addEventListener('click', () => loadCustomers());
    $('#customer-prev').addEventListener('click', () => { if (model.page > 1) { model.page--; loadCustomers(false); } });
    $('#customer-next').addEventListener('click', () => { model.page++; loadCustomers(false); });
    $('#customer-profile-refresh').addEventListener('click', () => refreshProfile());
    $('#customer-history-source').addEventListener('change', () => loadHistory(true));
    $('#customer-history-more').addEventListener('click', () => loadHistory(false));
    $('#wecom-account-select').addEventListener('change', () => loadCustomers());
    window.reloadCustomerWorkspace = loadCustomers;
})();
