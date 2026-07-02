import React, { useState } from 'react';
  import {
    View, Text, StyleSheet, TouchableOpacity,
    ScrollView, ActivityIndicator, Alert, TextInput,
  } from 'react-native';
  import { WebView } from 'react-native-webview';
  import { getDomains, createAccount, getToken, randUsername, randPassword } from '../utils/mailtm';
  import FloatingMailWindow from '../components/FloatingMailWindow';

  interface Props {
    onClose: () => void;
    onVerifyLink: (url: string) => void;
  }

  type Step = 'idle' | 'creating_mail' | 'filling_form' | 'waiting_verify' | 'done' | 'error';

  export default function RegisterScreen({ onClose, onVerifyLink }: Props) {
    const [step, setStep] = useState<Step>('idle');
    const [log, setLog] = useState<string[]>([]);
    const [account, setAccount] = useState<{username:string;email:string;password:string}|null>(null);
    const [showMailWindow, setShowMailWindow] = useState(false);
    const [webviewUrl, setWebviewUrl] = useState('');
    const [showWebview, setShowWebview] = useState(false);

    const addLog = (msg: string) => setLog(prev => [...prev, msg]);

    const startRegister = async () => {
      setLog([]);
      setStep('creating_mail');

      // 1. Tạo email mail.tm
      addLog('🔄 Lấy domain mail.tm...');
      const domains = await getDomains();
      if (!domains.length) { addLog('❌ Không lấy được domain'); setStep('error'); return; }
      const domain = domains[0];

      const username = randUsername();
      const password = randPassword();
      const email = `${username}@${domain}`;

      addLog(`✓ Email: ${email}`);
      addLog(`✓ Password: ${password}`);

      // 2. Tạo tài khoản mail.tm
      addLog('🔄 Tạo tài khoản mail.tm...');
      const mailOk = await createAccount(email, password);
      if (!mailOk) { addLog('❌ Tạo mail thất bại'); setStep('error'); return; }
      addLog('✅ Mail.tm OK');

      setAccount({ username, email, password });
      setStep('filling_form');
      addLog('🔄 Mở form đăng ký Replit...');

      // 3. Mở Replit signup với JS tự điền form
      setShowWebview(true);
      setWebviewUrl('https://replit.com/signup');
      setShowMailWindow(true);
      setStep('waiting_verify');
      addLog('⏳ Đang chờ email xác thực từ Replit...');
      addLog('💡 Cửa sổ mail.tm đã mở — sẽ tự động click link verify');
    };

    // JS inject vào Replit signup để tự điền form
    const getInjectJS = () => {
      if (!account) return '';
      return `
        (function() {
          function fill() {
            var u = document.querySelector('input[name="username"], input[placeholder*="username" i]');
            var e = document.querySelector('input[name="email"], input[type="email"]');
            var p = document.querySelector('input[name="password"], input[type="password"]');
            if (u) { u.value = '${account.username}'; u.dispatchEvent(new Event('input',{bubbles:true})); }
            if (e) { e.value = '${account.email}'; e.dispatchEvent(new Event('input',{bubbles:true})); }
            if (p) { p.value = '${account.password}'; p.dispatchEvent(new Event('input',{bubbles:true})); }
            if (u && e && p) {
              window.ReactNativeWebView.postMessage(JSON.stringify({type:'form_filled'}));
              return true;
            }
            return false;
          }
          var tries = 0;
          var iv = setInterval(function() {
            if (fill() || ++tries > 20) clearInterval(iv);
          }, 500);
        })();
      `;
    };

    const handleVerify = (url: string) => {
      setShowMailWindow(false);
      setWebviewUrl(url);
      setStep('done');
      addLog('🔗 Link verify tìm thấy!');
      addLog('✅ Đang xác thực...');
      onVerifyLink(url);
    };

    return (
      <View style={styles.root}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>➕ Đăng ký Replit tự động</Text>
          <TouchableOpacity onPress={onClose}>
            <Text style={styles.close}>✕ Đóng</Text>
          </TouchableOpacity>
        </View>

        {showWebview && account ? (
          <View style={{flex:1}}>
            <WebView
              source={{ uri: webviewUrl }}
              injectedJavaScript={getInjectJS()}
              javaScriptEnabled domStorageEnabled
              onMessage={(e) => {
                try {
                  const d = JSON.parse(e.nativeEvent.data);
                  if (d.type === 'form_filled') addLog('✅ Form đã điền tự động!');
                } catch {}
              }}
              style={{flex:1}}
            />
            {showMailWindow && account && (
              <FloatingMailWindow
                onClose={() => setShowMailWindow(false)}
                onVerifyLink={handleVerify}
                autoEmail={account.email}
                autoPassword={account.password}
              />
            )}
          </View>
        ) : (
          <ScrollView style={styles.body} contentContainerStyle={{padding:16}}>
            {step === 'idle' && (
              <>
                <Text style={styles.desc}>
                  Tool sẽ tự động:{'
'}
                  1. Tạo email @mail.tm ngẫu nhiên{'
'}
                  2. Mở form đăng ký Replit + tự điền{'
'}
                  3. Theo dõi inbox → tự click link verify{'
'}
                  4. Tài khoản Replit sẵn sàng ✅
                </Text>
                <TouchableOpacity style={styles.startBtn} onPress={startRegister}>
                  <Text style={styles.startBtnText}>🚀 Bắt đầu đăng ký tự động</Text>
                </TouchableOpacity>
              </>
            )}

            {/* Log output */}
            {log.map((l, i) => (
              <Text key={i} style={[styles.logLine,
                l.startsWith('✅')||l.startsWith('🔗') ? styles.logOk :
                l.startsWith('❌') ? styles.logErr : styles.logInfo
              ]}>{l}</Text>
            ))}

            {(step === 'creating_mail' || step === 'filling_form' || step === 'waiting_verify') && (
              <ActivityIndicator color="#58a6ff" style={{marginTop:16}} />
            )}

            {account && step !== 'idle' && (
              <View style={styles.accBox}>
                <Text style={styles.accTitle}>📋 Thông tin tài khoản</Text>
                <Text style={styles.accLine}>User: {account.username}</Text>
                <Text style={styles.accLine}>Email: {account.email}</Text>
                <Text style={styles.accLine}>Pass: {account.password}</Text>
              </View>
            )}

            {(step === 'done' || step === 'error') && (
              <TouchableOpacity style={styles.startBtn} onPress={startRegister}>
                <Text style={styles.startBtnText}>🔄 Đăng ký tài khoản mới</Text>
              </TouchableOpacity>
            )}
          </ScrollView>
        )}
      </View>
    );
  }

  const styles = StyleSheet.create({
    root: { flex: 1, backgroundColor: '#0d1117' },
    header: {
      flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
      padding: 14, backgroundColor: '#161b22',
      borderBottomWidth: 1, borderBottomColor: '#30363d',
    },
    title: { color: '#58a6ff', fontWeight: 'bold', fontSize: 15 },
    close: { color: '#6e7681', fontSize: 13 },
    body: { flex: 1 },
    desc: { color: '#8b949e', fontSize: 14, lineHeight: 22, marginBottom: 20 },
    startBtn: {
      backgroundColor: '#1f6feb', borderRadius: 8,
      padding: 14, alignItems: 'center', marginVertical: 12,
    },
    startBtnText: { color: '#fff', fontWeight: 'bold', fontSize: 15 },
    logLine: { fontSize: 12, marginBottom: 4, fontFamily: 'monospace' },
    logOk:   { color: '#3fb950' },
    logErr:  { color: '#f85149' },
    logInfo: { color: '#8b949e' },
    accBox: {
      backgroundColor: '#161b22', borderRadius: 8, padding: 14,
      borderWidth: 1, borderColor: '#30363d', marginTop: 16,
    },
    accTitle: { color: '#58a6ff', fontWeight: 'bold', marginBottom: 8 },
    accLine:  { color: '#c9d1d9', fontSize: 13, marginBottom: 4 },
  });