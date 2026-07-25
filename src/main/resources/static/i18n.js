(() => {
    const STORAGE_KEY = 'londonist-locale';
    const supported = new Set(['zh-CN', 'en']);
    let locale = supported.has(localStorage.getItem(STORAGE_KEY))
        ? localStorage.getItem(STORAGE_KEY) : 'zh-CN';

    const en = {
        '让房源推荐更准确。': 'Make every property recommendation count.',
        '统一管理公寓资料、报价库存与销售推荐，为学生快速匹配合适房源。': 'Manage residences, pricing, availability and sales preferences in one place.',
        '登录系统': 'Sign in',
        '使用您的后台账号继续。': 'Continue with your admin account.',
        '用户名': 'Username',
        '密码': 'Password',
        '登录': 'Sign in',
        '退出登录': 'Sign out',
        '智能问答': 'AI Assistant',
        '知识库': 'Knowledge Base',
        '公寓地址': 'Residences',
        '房型库存': 'Inventory',
        '推荐管理': 'Recommendations',
        '用户管理': 'Users',
        '个人设置': 'Settings',
        '会话历史': 'Conversations',
        '开始一次有依据的对话': 'Start a source-backed conversation',
        '选择知识库分类后提问，回答将附带检索到的引用片段。': 'Choose a knowledge category and ask a question. Answers include supporting references.',
        '发送': 'Send',
        '知识分类': 'Knowledge categories',
        '用于整理文档和限定检索范围。': 'Organise documents and define retrieval scope.',
        '新建分类': 'New category',
        '文档管理': 'Documents',
        '上传后会自动解析并写入向量库。': 'Uploaded files are parsed and indexed automatically.',
        '上传文档': 'Upload document',
        '全部分类': 'All categories',
        '请选择分类': 'Select a category',
        '搜索': 'Search',
        '上一页': 'Previous',
        '下一页': 'Next',
        '有效公寓': 'Active residences',
        '其他城市': 'Other cities',
        '公寓地址库': 'Residence directory',
        '维护全部城市的地址、设施、交通和附近学校。': 'Manage addresses, facilities, transport and nearby universities across all cities.',
        '新增公寓': 'Add residence',
        '导入地址 HTML': 'Import address HTML',
        '导入详情 MD': 'Import details MD',
        '全部城市': 'All cities',
        '全部区域': 'All regions',
        '东部': 'East',
        '西部': 'West',
        '北部': 'North',
        '南部': 'South',
        '公寓': 'Residence',
        '城市': 'City',
        '区域 / Zone': 'Area / Zone',
        '完整地址': 'Full address',
        '最近车站': 'Nearest station',
        '地图': 'Map',
        '操作': 'Actions',
        '查看地图': 'View map',
        '查看详情': 'View details',
        '编辑地址': 'Edit address',
        '删除': 'Delete',
        '更多操作': 'More actions',
        '房型库存与分档报价': 'Room inventory and tiered pricing',
        '一个房型维护一份库存，并可配置多个租期价格档位。': 'Maintain one availability record and multiple price tiers for each room type.',
        '批量导入': 'Bulk import',
        '新增房型': 'Add room type',
        '全部公寓': 'All residences',
        '全部库存': 'All inventory',
        '可预订': 'Available',
        '库存紧张': 'Limited',
        '已售罄': 'Sold out',
        '待确认': 'To confirm',
        '公寓 / 房型': 'Residence / Room',
        '可租日期': 'Available dates',
        '库存': 'Inventory',
        '租期价格档位': 'Price tiers',
        '更新时间': 'Updated',
        '最近导入': 'Recent imports',
        '相同业务记录仅由更晚的库存或价格时间覆盖。': 'Existing records are updated only by newer availability or pricing data.',
        '销售优先推荐': 'Sales recommendations',
        '优先级': 'Priority',
        '状态': 'Status',
        '内部备注': 'Internal note',
        '启用': 'Enabled',
        '停用': 'Disabled',
        '添加推荐公寓': 'Add recommendation',
        '用户': 'User',
        '姓名': 'Name',
        '角色': 'Role',
        '创建时间': 'Created',
        '新增用户': 'Add user',
        '保存资料': 'Save profile',
        '修改密码': 'Change password',
        '当前密码': 'Current password',
        '新密码': 'New password',
        '更新密码': 'Update password',
        '取消': 'Cancel',
        '保存用户': 'Save user',
        '保存公寓': 'Save residence',
        '保存房型': 'Save room type',
        '保存推荐配置': 'Save recommendation',
        '正常': 'Active',
        '禁用': 'Disabled',
        '有效': 'Active',
        '不适用': 'Not applicable',
        '语言': 'Language',
        '中文': '中文',
        '暂无历史会话': 'No conversations yet',
        '暂无描述': 'No description',
        '没有找到文档。': 'No documents found.',
        '没有找到用户。': 'No users found.',
        '还没有知识分类。': 'No knowledge categories yet.',
        '还没有公寓地址，请新增或导入数据。': 'No residences yet. Add one or import data.',
        '还没有结构化房型数据，可新增或批量导入。': 'No room inventory yet. Add one or run a bulk import.',
        '还没有销售推荐配置。': 'No sales recommendations yet.'
    };

    const placeholders = {
        '例如：admin': 'e.g. admin',
        '请输入密码': 'Enter your password',
        '输入你的问题…': 'Ask a question…',
        '搜索文档': 'Search documents',
        '按公寓名称模糊搜索': 'Search residence name',
        '搜索公寓、房型名称或编码': 'Search residence, room type or code',
        '按用户名或姓名搜索': 'Search username or name',
        '请输入姓名': 'Enter your name',
        '例如：arofan-house': 'e.g. arofan-house',
        '例如：本月主推': 'e.g. Featured this month'
    };

    const reverse = Object.fromEntries(Object.entries(en).map(([zh, value]) => [value, zh]));
    const reversePlaceholders = Object.fromEntries(
        Object.entries(placeholders).map(([zh, value]) => [value, zh]));

    function translateExact(value) {
        const trimmed = value.trim();
        if (!trimmed) return value;
        const translated = locale === 'en' ? en[trimmed] : reverse[trimmed];
        if (!translated) return translatePattern(value);
        return value.replace(trimmed, translated);
    }

    function translatePattern(value) {
        const patterns = locale === 'en' ? [
            [/共 (\d+) 个公寓/g, '$1 residences'],
            [/共 (\d+) 个房型/g, '$1 room types'],
            [/共 (\d+) 个文档/g, '$1 documents'],
            [/共 (\d+) 位用户/g, '$1 users'],
            [/最近同步：(.+)/g, 'Last synced: $1'],
            [/（剩余(\d+)间）/g, ' ($1 left)']
        ] : [
            [/^(\d+) residences$/g, '共 $1 个公寓'],
            [/^(\d+) room types$/g, '共 $1 个房型'],
            [/^(\d+) documents$/g, '共 $1 个文档'],
            [/^(\d+) users$/g, '共 $1 位用户'],
            [/^Last synced: (.+)$/g, '最近同步：$1'],
            [/ \((\d+) left\)/g, '（剩余$1间）']
        ];
        return patterns.reduce((text, [pattern, replacement]) =>
            text.replace(pattern, replacement), value);
    }

    function translateNode(root) {
        if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
        if (root.closest?.('.message-content, .reference-snippet')) return;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        const nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);
        nodes.forEach(node => {
            if (!node.parentElement?.closest('.message-content, .reference-snippet')) {
                node.nodeValue = translateExact(node.nodeValue);
            }
        });
        [root, ...root.querySelectorAll('input, textarea, button, [title], [aria-label]')]
            .forEach(element => {
                if (element.placeholder) {
                    const map = locale === 'en' ? placeholders : reversePlaceholders;
                    element.placeholder = map[element.placeholder] || element.placeholder;
                }
                if (element.title) element.title = translateExact(element.title);
                if (element.getAttribute('aria-label')) {
                    element.setAttribute('aria-label',
                        translateExact(element.getAttribute('aria-label')));
                }
            });
    }

    function apply() {
        document.documentElement.lang = locale;
        document.title = locale === 'en'
            ? 'Londonist · Property Intelligence Hub'
            : 'Londonist · 智能房源中心';
        translateNode(document.body);
        const switcher = document.querySelector('#locale-switcher');
        if (switcher) switcher.value = locale;
    }

    document.addEventListener('DOMContentLoaded', () => {
        apply();
        document.querySelector('#locale-switcher')?.addEventListener('change', event => {
            const next = event.target.value;
            if (!supported.has(next)) return;
            localStorage.setItem(STORAGE_KEY, next);
            window.location.reload();
        });
        new MutationObserver(mutations => mutations.forEach(mutation =>
            mutation.addedNodes.forEach(node => {
                if (node.nodeType === Node.TEXT_NODE && node.parentElement) {
                    if (!node.parentElement.closest('.message-content, .reference-snippet')) {
                        node.nodeValue = translateExact(node.nodeValue);
                    }
                } else {
                    translateNode(node);
                }
            }))).observe(document.body, {childList: true, subtree: true});
    });

    window.i18n = {
        locale: () => locale,
        formatLocale: () => locale === 'en' ? 'en-GB' : 'zh-CN',
        translate: translateExact
    };
})();
