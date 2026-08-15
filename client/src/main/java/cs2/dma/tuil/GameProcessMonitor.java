package cs2.dma.tuil;

import vmm.IVmm;
import vmm.IVmmProcess;

public class GameProcessMonitor {
    private final IVmm vmm;
    private static final String PROCESS_NAME = "cs2.exe";
    private static final long POLL_INTERVAL = 5000;
    private static final long ERROR_BACKOFF = 10000; // 10 second backoff on errors
    private IVmmProcess currentProcess;

    public GameProcessMonitor(IVmm vmm) {
        this.vmm = vmm;
    }

    /**
     * One-shot process lookup (does not block). Returns the cs2.exe process if
     * present and valid, otherwise null. Callers poll at their own cadence.
     */
    public IVmmProcess pollProcess() {
        try {
            IVmmProcess process = findCS2Process();
            if (process != null && isProcessValid(process)) {
                currentProcess = process;
                return process;
            }
        } catch (Exception e) {
            System.out.println("[-] Error finding process: " + e.getMessage());
        }
        return null;
    }

    public boolean isProcessValid(IVmmProcess process) {
        try {
            return process != null && process.getName().equals(PROCESS_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isCurrentProcessValid() {
        return isProcessValid(currentProcess);
    }

    /** Drop the cached process so the next waitForProcess() re-attaches. */
    public void reset() {
        currentProcess = null;
    }

    private IVmmProcess findCS2Process() {
        try {
            for (IVmmProcess process : vmm.processGetAll()) {
                if (PROCESS_NAME.equals(process.getName())) {
                    return process;
                }
            }
        } catch (Exception e) {
            System.out.println("[-] Error finding process: " + e.getMessage());
        }
        return null;
    }
}