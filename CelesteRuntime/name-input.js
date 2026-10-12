(() => {
  let dialog, input, result = '';
  window.celesteAndroidOpenNamePrompt = currentName => {
    if (!dialog) {
      dialog = document.createElement('dialog');
      dialog.id = 'celeste-name-prompt';
      dialog.style.cssText = 'width:min(420px,90vw);background:#192239;color:white;padding:24px;border:1px solid #8998b9;border-radius:12px;font:16px sans-serif';
      dialog.innerHTML = '<form method="dialog"><label for="celeste-name-input">Save name</label><input id="celeste-name-input" maxlength="12" autocomplete="off" style="display:block;width:100%;box-sizing:border-box;margin:12px 0;padding:10px;font:20px sans-serif"><div style="display:flex;justify-content:flex-end;gap:12px"><button value="cancel">Cancel</button><button value="save">Save</button></div></form>';
      input = dialog.querySelector('input');
      dialog.addEventListener('close', () => {
        if (dialog.returnValue === 'save' && input.value.trim()) result = '1' + input.value.trim();
        else result = '0';
      });
      document.body.appendChild(dialog);
    }
    if (dialog.open) return;
    input.value = currentName;
    dialog.returnValue = '';
    dialog.showModal();
    input.focus();
    input.select();
  };
  window.celesteAndroidConsumeNamePrompt = () => {
    const value = result;
    result = '';
    return value;
  };
})();
