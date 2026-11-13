"""The model: a few convolutions over the moves, then two layers of recurrent memory reading them both
ways, and for each step how likely each letter is (CTC: letters where the writing says them, blanks
between). About a million weights; a few megabytes as ONNX."""
import torch
from torch import nn


class Rukopys(nn.Module):
    def __init__(self, classes, width=160):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv1d(3, 64, 5, padding=2), nn.ReLU(),
            nn.Conv1d(64, 128, 5, stride=2, padding=2), nn.ReLU(),
            nn.Conv1d(128, 128, 3, padding=1), nn.ReLU(),
        )
        self.rnn = nn.GRU(128, width, num_layers=2, bidirectional=True, batch_first=True, dropout=0.2)
        self.out = nn.Linear(2 * width, classes)

    @staticmethod
    def steps(lengths):
        """How many output steps [lengths] input points give (the second convolution halves them)."""
        return (lengths + 1) // 2

    def forward(self, x, lengths=None):
        # x: [batch, points, 3]
        h = self.conv(x.transpose(1, 2)).transpose(1, 2)
        if lengths is not None:
            packed = nn.utils.rnn.pack_padded_sequence(h, self.steps(lengths).cpu(), batch_first=True, enforce_sorted=False)
            h, _ = self.rnn(packed)
            h, _ = nn.utils.rnn.pad_packed_sequence(h, batch_first=True, total_length=x.shape[1] // 2 + x.shape[1] % 2)
        else:
            h, _ = self.rnn(h)
        return self.out(h).log_softmax(-1)
