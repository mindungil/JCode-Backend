-- The maintained LAB image provides IDE + VNC, not a Jupyter web server.
-- CUSTOM image capabilities remain explicitly configurable.
UPDATE course SET use_jupyter = FALSE, version = version + 1
WHERE environment_profile = 'LAB' AND use_jupyter = TRUE;
